/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.discovery;

import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRATION_PREFIX;
import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRY_PREFIX;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.core.Response.Status;

import com.netflix.discovery.shared.resolver.EndpointRandomizer;
import com.netflix.discovery.shared.resolver.ResolverUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckCallbackToHandlerBridge;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpClients;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaTransportConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.Jersey1DiscoveryClientOptionalArgs;
import com.netflix.discovery.shared.transport.jersey.Jersey1TransportClientFactories;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.discovery.util.ThresholdLevelsMetric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;

/**
 * The class that is instrumental for interactions with <tt>Eureka Server</tt>.
 *
 * <p>
 * <tt>Eureka Client</tt> is responsible for a) <em>Registering</em> the
 * instance with <tt>Eureka Server</tt> b) <em>Renewal</em>of the lease with
 * <tt>Eureka Server</tt> c) <em>Cancellation</em> of the lease from
 * <tt>Eureka Server</tt> during shutdown
 * <p>
 * d) <em>Querying</em> the list of services/instances registered with
 * <tt>Eureka Server</tt>
 * <p>
 *
 * <p>
 * <tt>Eureka Client</tt> needs a configured list of <tt>Eureka Server</tt>
 * {@link java.net.URL}s to talk to.These {@link java.net.URL}s are typically amazon elastic eips
 * which do not change. All of the functions defined above fail-over to other
 * {@link java.net.URL}s specified in the list in the case of failure.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * @author Spencer Gibb
 *
 */
@Singleton
public class DiscoveryClient implements EurekaClient {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClient.class);

    // Constants
    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private static final String VALUE_DELIMITER = ",";
    private static final String COMMA_STRING = VALUE_DELIMITER;

    /**
     * @deprecated here for legacy support as the client config has moved to be an instance variable
     */
    @Deprecated
    private static EurekaClientConfig staticClientConfig;

    // Timers
    private static final String PREFIX = "DiscoveryClient_";
    private final Counter RECONCILE_HASH_CODES_MISMATCH = Monitors.newCounter(PREFIX + "ReconcileHashCodeMismatch");
    private final com.netflix.servo.monitor.Timer FETCH_REGISTRY_TIMER = Monitors
            .newTimer(PREFIX + "FetchRegistry");
    private final Counter REREGISTER_COUNTER = Monitors.newCounter(PREFIX
            + "Reregister");

    // instance variables
    /**
     * A scheduler to be used for the following 3 tasks:
     * - updating service urls
     * - scheduling a TimedSuperVisorTask
     */
    private final ScheduledExecutorService scheduler;
    // additional executors for supervised subtasks
    private final ThreadPoolExecutor heartbeatExecutor;
    private final ThreadPoolExecutor cacheRefreshExecutor;

    private TimedSupervisorTask cacheRefreshTask;
    private TimedSupervisorTask heartbeatTask;

    private final Provider<HealthCheckHandler> healthCheckHandlerProvider;
    private final Provider<HealthCheckCallback> healthCheckCallbackProvider;
    private final PreRegistrationHandler preRegistrationHandler;
    /*
        这个是根据AppName当key , Applications当成value存储在map之中
     */
    private final AtomicReference<Applications> localRegionApps = new AtomicReference<Applications>();
    private final Lock fetchRegistryUpdateLock = new ReentrantLock();
    // monotonically increasing generation counter to ensure stale threads do not reset registry to an older version
    private final AtomicLong fetchRegistryGeneration;
    private final ApplicationInfoManager applicationInfoManager;
    private final InstanceInfo instanceInfo;
    private final AtomicReference<String> remoteRegionsToFetch;
    private final AtomicReference<String[]> remoteRegionsRef;
    private final InstanceRegionChecker instanceRegionChecker;

    private final EndpointUtils.ServiceUrlRandomizer urlRandomizer;
    private final EndpointRandomizer endpointRandomizer;
    private final Provider<BackupRegistry> backupRegistryProvider;
    private final EurekaTransport eurekaTransport;

    private final AtomicReference<HealthCheckHandler> healthCheckHandlerRef = new AtomicReference<>();
    private volatile Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<>();
    private volatile InstanceInfo.InstanceStatus lastRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
    private final CopyOnWriteArraySet<EurekaEventListener> eventListeners = new CopyOnWriteArraySet<>();

    private String appPathIdentifier;
    private ApplicationInfoManager.StatusChangeListener statusChangeListener;

    private InstanceInfoReplicator instanceInfoReplicator;

    private volatile int registrySize = 0;
    private volatile long lastSuccessfulRegistryFetchTimestamp = -1;
    private volatile long lastSuccessfulHeartbeatTimestamp = -1;
    private final ThresholdLevelsMetric heartbeatStalenessMonitor;
    private final ThresholdLevelsMetric registryStalenessMonitor;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    protected final EurekaClientConfig clientConfig;
    protected final EurekaTransportConfig transportConfig;

    private final long initTimestampMs;
    private final int initRegistrySize;

    private final Stats stats = new Stats();

    private static final class EurekaTransport {
        private ClosableResolver bootstrapResolver;
        private TransportClientFactory transportClientFactory;

        private EurekaHttpClient registrationClient;
        private EurekaHttpClientFactory registrationClientFactory;

        private EurekaHttpClient queryClient;
        private EurekaHttpClientFactory queryClientFactory;

        void shutdown() {
            if (registrationClientFactory != null) {
                registrationClientFactory.shutdown();
            }

            if (queryClientFactory != null) {
                queryClientFactory.shutdown();
            }

            if (registrationClient != null) {
                registrationClient.shutdown();
            }

            if (queryClient != null) {
                queryClient.shutdown();
            }

            if (transportClientFactory != null) {
                transportClientFactory.shutdown();
            }

            if (bootstrapResolver != null) {
                bootstrapResolver.shutdown();
            }
        }
    }

    public static class DiscoveryClientOptionalArgs extends Jersey1DiscoveryClientOptionalArgs {

    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config) {
        this(myInfo, config, null);
    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    /**
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config) {
        this(applicationInfoManager, config, null);
    }

    /**
     * @deprecated use the version that take {@link com.netflix.discovery.AbstractDiscoveryClientOptionalArgs} instead
     */
    @Deprecated
    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, (AbstractDiscoveryClientOptionalArgs) args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, args, ResolverUtils::randomize);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args, EndpointRandomizer randomizer) {
        this(applicationInfoManager, config, args, new Provider<BackupRegistry>() {
            private volatile BackupRegistry backupRegistryInstance;

            @Override
            public synchronized BackupRegistry get() {
                if (backupRegistryInstance == null) {
                    String backupRegistryClassName = config.getBackupRegistryImpl();
                    if (null != backupRegistryClassName) {
                        try {
                            backupRegistryInstance = (BackupRegistry) Class.forName(backupRegistryClassName).newInstance();
                            logger.info("Enabled backup registry of type {}", backupRegistryInstance.getClass());
                        } catch (InstantiationException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (IllegalAccessException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (ClassNotFoundException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        }
                    }

                    if (backupRegistryInstance == null) {
                        logger.warn("Using default backup registry implementation which does not do anything.");
                        backupRegistryInstance = new NotImplementedRegistryImpl();
                    }
                }

                return backupRegistryInstance;
            }
        }, randomizer);
    }

    /**
     * @deprecated Use {@link #DiscoveryClient(ApplicationInfoManager, EurekaClientConfig, AbstractDiscoveryClientOptionalArgs, Provider<BackupRegistry>, EndpointRandomizer)}
     */
    @Deprecated
    DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider) {
        this(applicationInfoManager, config, args, backupRegistryProvider, ResolverUtils::randomize);
    }


    /** description: 初始化DiscoveryClient注册者实例构造方法 , EurekaBootStrap真实调用的类,相当于是用于构造一个EurekaClient,不管EurekaClient或是EurekaServer都会进行调用
     * 1. 初始化检查和监听
     * 2. EurekaClient注册相关信息InstanceInfo实例的构建
     * 3. 构建服务实例
     * 4. 抓取注册表相关信息
     * 5. 注册EurekaClient实例
     * 6. 构建了一个支持定时调度的线程池scheduler
     * 7. 构建了一个心跳机制的线程池heartbeatExecutor
     * 8. 构建了一个缓存刷新缓存的线程池cacheRefreshExecutor
     * 9. 构建一个EurekaClient和EurekaServer进行网络通信的组件EurekaTransport
     * 10. 初始化网络请求组件和调度任务
     * 11. 启动一堆调度任务
     *     1) 定时抓取调度表的注册任务
     *     2) 给EurekaServer定时发送心跳的调度任务
     *     3) 进行服务实例信息复制的调度任务
     *     4) 注册了状态变更的监听器
     *
     * 槽点:
     *  1. 服务注册,不应该放在InstanceInfoReplicator方法之中,语义不明朗
     *  2. 负责发送请求的HttpClient,类体系过于复杂,导致人根本找不到对应的client
     *      其实是在AbstractJersey2EurekaHttpClient
     *
     * 核心机制
     *  1. EurekaClient的服务注册是在 InstanceInfoReplicator 之中
     *  2. 实际发送HTTP请求是在AbstractJersey2EurekaHttpClient之中
     *
     *
     * @param applicationInfoManager ApplicationInfoManager类,通过InstanceInfo和EurekaInstanceConfig对象构造的配置类,对Client的配置进行管理
     * @param config 具体实现是DefaultEurekaClientConfig , 有namespace (管理命名空间) \ transportConfig (管理传输协议) \ configInstance()等信息
     * @param args 从EurekaBootStrap类进来的话,应该是为null
     * @param backupRegistryProvider
     * @param endpointRandomizer  ResolverUtils::randomize
     * @return:
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/3/17 10:34
     */
    @Inject
    DiscoveryClient(
            ApplicationInfoManager applicationInfoManager,
            EurekaClientConfig config,
            AbstractDiscoveryClientOptionalArgs args,
            Provider<BackupRegistry> backupRegistryProvider, EndpointRandomizer endpointRandomizer) {

        if (args != null) {
            //健康检查
            this.healthCheckHandlerProvider = args.healthCheckHandlerProvider;
            this.healthCheckCallbackProvider = args.healthCheckCallbackProvider;
            //监听
            this.eventListeners.addAll(args.getEventListeners());
            this.preRegistrationHandler = args.preRegistrationHandler;
        } else {
            this.healthCheckCallbackProvider = null;
            this.healthCheckHandlerProvider = null;
            this.preRegistrationHandler = null;
        }
        
        this.applicationInfoManager = applicationInfoManager;
        /*
         * 第二步构建的服务实例
         */
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        /*
         *   刚刚构建的配置信息
         */
        clientConfig = config;
        staticClientConfig = clientConfig;
        transportConfig = config.getTransportConfig();
        instanceInfo = myInfo;
        /*
         * 构建服务实例的 appName/id  如ServiceA/001
         */
        if (myInfo != null) {
            appPathIdentifier = instanceInfo.getAppName() + "/" + instanceInfo.getId();
        } else {
            logger.warn("Setting instanceInfo to a passed in null value");
        }

        this.backupRegistryProvider = backupRegistryProvider;
        this.endpointRandomizer = endpointRandomizer;
        this.urlRandomizer = new EndpointUtils.InstanceInfoBasedUrlRandomizer(instanceInfo);
        localRegionApps.set(new Applications());
        /*
         * 抓取注册表相关的东西
         */
        fetchRegistryGeneration = new AtomicLong(0);

        remoteRegionsToFetch = new AtomicReference<String>(clientConfig.fetchRegistryForRemoteRegions());
        remoteRegionsRef = new AtomicReference<>(remoteRegionsToFetch.get() == null ? null : remoteRegionsToFetch.get().split(","));
        /*
         * shouldFetchRegistry 默认是false
         * 去不去其他的eureka-server中抓取注册表.
         * 如果是单个eureka启动,则不用设置
         * 如果是集群模式,则需要进行设置
         */
        if (config.shouldFetchRegistry()) {
            this.registryStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRY_PREFIX + "lastUpdateSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.registryStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }
        /*
         * registration.enabled
         * 要不要去其他eureka server上去进行注册
         * 如果是单个,不需要
         * 如果是集群,则需要
         */
        if (config.shouldRegisterWithEureka()) {
            this.heartbeatStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRATION_PREFIX + "lastHeartbeatSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.heartbeatStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        logger.info("Initializing Eureka in region {}", clientConfig.getRegion());

        if (!config.shouldRegisterWithEureka() && !config.shouldFetchRegistry()) {
            logger.info("Client configured to neither register nor query for data.");
            scheduler = null;
            heartbeatExecutor = null;
            cacheRefreshExecutor = null;
            eurekaTransport = null;
            instanceRegionChecker = new InstanceRegionChecker(new PropertyBasedAzToRegionMapper(config), clientConfig.getRegion());

            // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
            // to work with DI'd DiscoveryClient
            DiscoveryManager.getInstance().setDiscoveryClient(this);
            DiscoveryManager.getInstance().setEurekaClientConfig(config);

            initTimestampMs = System.currentTimeMillis();
            initRegistrySize = this.getApplications().size();
            registrySize = initRegistrySize;
            logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                    initTimestampMs, initRegistrySize);

            return;  // no need to setup up an network tasks and we are done
        }

        try {
            // default size of 2 - 1 each for heartbeat and cacheRefresh
            /*
             * 构建了一个支持定时调度的线程池scheduler
             */
            scheduler = Executors.newScheduledThreadPool(2,
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-%d")
                            .setDaemon(true)
                            .build());
            /*
             * 构建了一个心跳机制的线程池heartbeatExecutor
             */
            heartbeatExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getHeartbeatExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-HeartbeatExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff
            /*
             * 构建了一个缓存刷新缓存的线程池cacheRefreshExecutor
             */
            cacheRefreshExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getCacheRefreshExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-CacheRefreshExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            /*
             * 构建一个EurekaClient和EurekaServer进行网络通信的组件EurekaTransport
             */
            eurekaTransport = new EurekaTransport();
            /*
             * 初始化网络请求组件和调度任务
             */
            scheduleServerEndpointTask(eurekaTransport, args);

            AzToRegionMapper azToRegionMapper;
            if (clientConfig.shouldUseDnsForFetchingServiceUrls()) {
                azToRegionMapper = new DNSBasedAzToRegionMapper(clientConfig);
            } else {
                azToRegionMapper = new PropertyBasedAzToRegionMapper(clientConfig);
            }
            if (null != remoteRegionsToFetch.get()) {
                azToRegionMapper.setRegionsToFetch(remoteRegionsToFetch.get().split(","));
            }
            instanceRegionChecker = new InstanceRegionChecker(azToRegionMapper, clientConfig.getRegion());
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DiscoveryClient!", e);
        }
        /*
         * 这里是如果要抓取注册表
         */
        if (clientConfig.shouldFetchRegistry()) {
            try {
                /*
                 * 抓取注册表
                 */
                boolean primaryFetchRegistryResult = fetchRegistry(false);
                if (!primaryFetchRegistryResult) {
                    logger.info("Initial registry fetch from primary servers failed");
                }
                boolean backupFetchRegistryResult = true;
                /*
                 * 抓取失败了 , 则从备份的注册表进行抓取
                 */
                if (!primaryFetchRegistryResult && !fetchRegistryFromBackup()) {
                    backupFetchRegistryResult = false;
                    logger.info("Initial registry fetch from backup servers failed");
                }
                /*
                  还失败了,抛异常
                 */
                if (!primaryFetchRegistryResult && !backupFetchRegistryResult && clientConfig.shouldEnforceFetchRegistryAtInit()) {
                    throw new IllegalStateException("Fetch registry error at startup. Initial fetch failed.");
                }
            } catch (Throwable th) {
                logger.error("Fetch registry error at startup: {}", th.getMessage());
                throw new IllegalStateException(th);
            }
        }

        // call and execute the pre registration handler before all background tasks (inc registration) is started
        if (this.preRegistrationHandler != null) {
            this.preRegistrationHandler.beforeRegistration();
        }

        if (clientConfig.shouldRegisterWithEureka() && clientConfig.shouldEnforceRegistrationAtInit()) {
            try {
                if (!register() ) {
                    throw new IllegalStateException("Registration error at startup. Invalid server response.");
                }
            } catch (Throwable th) {
                logger.error("Registration error at startup: {}", th.getMessage());
                throw new IllegalStateException(th);
            }
        }

        // finally, init the schedule tasks (e.g. cluster resolvers, heartbeat, instanceInfo replicator, fetch
        /*
         * initScheduledTasks() 启动一堆调度任务
         * 1) 定时抓取调度表的注册任务
         * 2) 给EurekaServer定时发送心跳的调度任务
         * 3) 进行服务实例信息复制的调度任务
         * 4) 注册了状态变更的监听器
         */
        initScheduledTasks();

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register timers", e);
        }

        // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
        // to work with DI'd DiscoveryClient
        DiscoveryManager.getInstance().setDiscoveryClient(this);
        DiscoveryManager.getInstance().setEurekaClientConfig(config);

        initTimestampMs = System.currentTimeMillis();
        initRegistrySize = this.getApplications().size();
        registrySize = initRegistrySize;
        logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                initTimestampMs, initRegistrySize);
    }
    /** description: 初始化一些网络通信的代码
     * 1. 初始化网络请求组件 , 框架采用Jersey
     * 2. 拉取服务的注册表
     * @param eurekaTransport 网络通信对象
     * @param args 参数为0
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/3/17 20:37
     */
    private void scheduleServerEndpointTask(EurekaTransport eurekaTransport,
                                            AbstractDiscoveryClientOptionalArgs args) {

            
        Collection<?> additionalFilters = args == null
                ? Collections.emptyList()
                : args.additionalFilters;
        /*
         * 网络请求框架
         */
        EurekaJerseyClient providedJerseyClient = args == null
                ? null
                : args.eurekaJerseyClient;
        
        TransportClientFactories argsTransportClientFactories = null;
        if (args != null && args.getTransportClientFactories() != null) {
            argsTransportClientFactories = args.getTransportClientFactories();
        }
        
        // Ignore the raw types warnings since the client filter interface changed between jersey 1/2
        @SuppressWarnings("rawtypes")
        TransportClientFactories transportClientFactories = argsTransportClientFactories == null
                ? new Jersey1TransportClientFactories()
                : argsTransportClientFactories;
                
        Optional<SSLContext> sslContext = args == null
                ? Optional.empty()
                : args.getSSLContext();
        Optional<HostnameVerifier> hostnameVerifier = args == null
                ? Optional.empty()
                : args.getHostnameVerifier();

        // If the transport factory was not supplied with args, assume they are using jersey 1 for passivity
        eurekaTransport.transportClientFactory = providedJerseyClient == null
                ? transportClientFactories.newTransportClientFactory(clientConfig, additionalFilters, applicationInfoManager.getInfo(), sslContext, hostnameVerifier)
                : transportClientFactories.newTransportClientFactory(additionalFilters, providedJerseyClient);

        ApplicationsResolver.ApplicationsSource applicationsSource = new ApplicationsResolver.ApplicationsSource() {
            @Override
            public Applications getApplications(int stalenessThreshold, TimeUnit timeUnit) {
                long thresholdInMs = TimeUnit.MILLISECONDS.convert(stalenessThreshold, timeUnit);
                long delay = getLastSuccessfulRegistryFetchTimePeriod();
                if (delay > thresholdInMs) {
                    logger.info("Local registry is too stale for local lookup. Threshold:{}, actual:{}",
                            thresholdInMs, delay);
                    return null;
                } else {
                    return localRegionApps.get();
                }
            }
        };

        eurekaTransport.bootstrapResolver = EurekaHttpClients.newBootstrapResolver(
                clientConfig,
                transportConfig,
                eurekaTransport.transportClientFactory,
                applicationInfoManager.getInfo(),
                applicationsSource,
                endpointRandomizer
        );
        /*
         *  注册请求走这里,来初始化EurekaTransport
         */
        if (clientConfig.shouldRegisterWithEureka()) {
            EurekaHttpClientFactory newRegistrationClientFactory = null;
            /*
             * 初始化用来注册的client
             */
            EurekaHttpClient newRegistrationClient = null;
            try {
                newRegistrationClientFactory = EurekaHttpClients.registrationClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        transportConfig
                );
                newRegistrationClient = newRegistrationClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.registrationClientFactory = newRegistrationClientFactory;
            eurekaTransport.registrationClient = newRegistrationClient;
        }

        // new method (resolve from primary servers for read)
        // Configure new transport layer (candidate for injecting in the future)
        /*
         * 拉取服务的注册表
         */
        if (clientConfig.shouldFetchRegistry()) {
            EurekaHttpClientFactory newQueryClientFactory = null;
            EurekaHttpClient newQueryClient = null;
            try {
                newQueryClientFactory = EurekaHttpClients.queryClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        clientConfig,
                        transportConfig,
                        applicationInfoManager.getInfo(),
                        applicationsSource,
                        endpointRandomizer
                );
                newQueryClient = newQueryClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.queryClientFactory = newQueryClientFactory;
            eurekaTransport.queryClient = newQueryClient;
        }
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return clientConfig;
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        return localRegionApps.get();
    }

    @Override
    public Applications getApplicationsForARegion(@Nullable String region) {
        if (instanceRegionChecker.isLocalRegion(region)) {
            return localRegionApps.get();
        } else {
            return remoteRegionVsApps.get(region);
        }
    }

    public Set<String> getAllKnownRegions() {
        String localRegion = instanceRegionChecker.getLocalRegion();
        if (!remoteRegionVsApps.isEmpty()) {
            Set<String> regions = remoteRegionVsApps.keySet();
            Set<String> toReturn = new HashSet<String>(regions);
            toReturn.add(localRegion);
            return toReturn;
        } else {
            return Collections.singleton(localRegion);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(java.lang.String)
     */
    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> instancesList = new ArrayList<>();
        for (Application app : this.getApplications()
                .getRegisteredApplications()) {
            InstanceInfo instanceInfo = app.getByInstanceId(id);
            if (instanceInfo != null) {
                instancesList.add(instanceInfo);
            }
        }
        return instancesList;
    }

    /**
     * Register {@link HealthCheckCallback} with the eureka client.
     *
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     *
     * @deprecated Use
     */
    @Deprecated
    @Override
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        if (instanceInfo == null) {
            logger.error("Cannot register a listener for instance info since it is null!");
        }
        if (callback != null) {
            healthCheckHandlerRef.set(new HealthCheckCallbackToHandlerBridge(callback));
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        if (instanceInfo == null) {
            logger.error("Cannot register a healthcheck handler when instance info is null!");
        }
        if (healthCheckHandler != null) {
            this.healthCheckHandlerRef.set(healthCheckHandler);
            // schedule an onDemand update of the instanceInfo when a new healthcheck handler is registered
            if (instanceInfoReplicator != null) {
                instanceInfoReplicator.onDemandUpdate();
            }
        }
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return this.eventListeners.remove(eventListener);
    }

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return getInstancesByVipAddress(vipAddress, secure, instanceRegionChecker.getLocalRegion());
    }

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure - true if it is a secure vip address, false otherwise
     * @param region - region from which the instances are to be fetched. If <code>null</code> then local region is
     *               assumed.
     *
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure,
                                                       @Nullable String region) {
        if (vipAddress == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address cannot be null");
        }
        Applications applications;
        if (instanceRegionChecker.isLocalRegion(region)) {
            applications = this.localRegionApps.get();
        } else {
            applications = remoteRegionVsApps.get(region);
            if (null == applications) {
                logger.debug("No applications are defined for region {}, so returning an empty instance list for vip "
                        + "address {}.", region, vipAddress);
                return Collections.emptyList();
            }
        }

        if (!secure) {
            return applications.getInstancesByVirtualHostName(vipAddress);
        } else {
            return applications.getInstancesBySecureVirtualHostName(vipAddress);

        }

    }

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param appName
     *            - The applicationName to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(
            String vipAddress, String appName, boolean secure) {

        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        if (vipAddress == null && appName == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address and application name cannot both be null");
        } else if (vipAddress != null && appName == null) {
            return getInstancesByVipAddress(vipAddress, secure);
        } else if (vipAddress == null && appName != null) {
            Application application = getApplication(appName);
            if (application != null) {
                result = application.getInstances();
            }
            return result;
        }

        String instanceVipAddress;
        for (Application app : getApplications().getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                if (secure) {
                    instanceVipAddress = instance.getSecureVipAddress();
                } else {
                    instanceVipAddress = instance.getVIPAddress();
                }
                if (instanceVipAddress == null) {
                    continue;
                }
                String[] instanceVipAddresses = instanceVipAddress
                        .split(COMMA_STRING);

                // If the VIP Address is delimited by a comma, then consider to
                // be a list of VIP Addresses.
                // Try to match at least one in the list, if it matches then
                // return the instance info for the same
                for (String vipAddressFromList : instanceVipAddresses) {
                    if (vipAddress.equalsIgnoreCase(vipAddressFromList.trim())
                            && appName.equalsIgnoreCase(instance.getAppName())) {
                        result.add(instance);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.discovery.shared.LookupService#getNextServerFromEureka(java
     * .lang.String, boolean)
     */
    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        List<InstanceInfo> instanceInfoList = this.getInstancesByVipAddress(
                virtualHostname, secure);
        if (instanceInfoList == null || instanceInfoList.isEmpty()) {
            throw new RuntimeException("No matches for the virtual host name :"
                    + virtualHostname);
        }
        Applications apps = this.localRegionApps.get();
        int index = (int) (apps.getNextIndex(virtualHostname,
                secure).incrementAndGet() % instanceInfoList.size());
        return instanceInfoList.get(index);
    }

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl
     *            - The string representation of the service url.
     * @return - The registry information containing all applications.
     */
    @Override
    public Applications getApplications(String serviceUrl) {
        try {
            EurekaHttpResponse<Applications> response = clientConfig.getRegistryRefreshSingleVipAddress() == null
                    ? eurekaTransport.queryClient.getApplications()
                    : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress());
            if (response.getStatusCode() == Status.OK.getStatusCode()) {
                logger.debug(PREFIX + "{} -  refresh status: {}", appPathIdentifier, response.getStatusCode());
                return response.getEntity();
            }
            logger.info(PREFIX + "{} - was unable to refresh its cache! This periodic background refresh will be retried in {} seconds. status = {}",
                    appPathIdentifier, clientConfig.getRegistryFetchIntervalSeconds(), response.getStatusCode());
        } catch (Throwable th) {
            logger.info(PREFIX + "{} - was unable to refresh its cache! This periodic background refresh will be retried in {} seconds. status = {} stacktrace = {}",
                    appPathIdentifier, clientConfig.getRegistryFetchIntervalSeconds(), th.getMessage(), ExceptionUtils.getStackTrace(th));
        }
        return null;
    }

    /** description:  Register with the eureka service by making the appropriate REST call.
     * 1. 这里找的是EurekaTransport
     * @return: boolean
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/3/18 7:33
     */
    boolean register() throws Throwable {

        logger.info(PREFIX + "{}: registering service...", appPathIdentifier);
        EurekaHttpResponse<Void> httpResponse;
        try {
            httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
        } catch (Exception e) {
            logger.warn(PREFIX + "{} - registration failed {}", appPathIdentifier, e.getMessage(), e);
            throw e;
        }
        if (logger.isInfoEnabled()) {
            logger.info(PREFIX + "{} - registration status: {}", appPathIdentifier, httpResponse.getStatusCode());
        }
        return httpResponse.getStatusCode() == Status.NO_CONTENT.getStatusCode();
    }

    /**
     * Renew with the eureka service by making the appropriate REST call
     */
    boolean renew() {
        EurekaHttpResponse<InstanceInfo> httpResponse;
        try {
            // 发送请求为  http://localhost:8080/v2/apps/appName/id1  调用ApplicationResource的{id}方法,返回一个InstanceResource
            httpResponse = eurekaTransport.registrationClient.sendHeartBeat(instanceInfo.getAppName(), instanceInfo.getId(), instanceInfo, null);
            logger.debug(PREFIX + "{} - Heartbeat status: {}", appPathIdentifier, httpResponse.getStatusCode());
            if (httpResponse.getStatusCode() == Status.NOT_FOUND.getStatusCode()) {
                REREGISTER_COUNTER.increment();
                logger.info(PREFIX + "{} - Re-registering apps/{}", appPathIdentifier, instanceInfo.getAppName());
                long timestamp = instanceInfo.setIsDirtyWithTime();
                boolean success = register();
                if (success) {
                    instanceInfo.unsetIsDirty(timestamp);
                }
                return success;
            }
            return httpResponse.getStatusCode() == Status.OK.getStatusCode();
        } catch (Throwable e) {
            logger.error(PREFIX + "{} - was unable to send heartbeat!", appPathIdentifier, e);
            return false;
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     *
     * @param instanceZone The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(clientConfig, instanceZone, preferSameZone);
    }

    /**
     * Shuts down Eureka Client. Also sends a deregistration request to the
     * eureka server.
     *  EurekaClient主动下线
     *  1. 将自己的状态修改为DOWN
     *  2. 然后发送给EurekaServer
     *      1). apps/appName/{id}返回一个InstanceResource , 然后调用InstanceResource的DELETE方法
     *  3. EurekaServer执行Cancel方法
     *      1). 执行检查,获取当前lease
     *      2). 调用lease的cancel方法
     *          (1). 把evictionTimestamp时间修改为当前时间
     *      3). 将lease的InstanceInfo的状态修改为DELETED
     *      4). 将这个lease放到recentlyChangedQueue , 最近改变的queue中
     *  4. EurekaServer定时任务
     *      1). 只会拉取最近三分钟有变化的增量注册表
     *      2). 如果定时任务被关掉,不会有续约操作
     *      3). 定时任务将不会将本服务实例拉取到注册表中
     *      4). 其他EurekaClient端也不会对数据进行拉取
     *  5. 过期读写缓存中的数据
     */
    @PreDestroy
    @Override
    public synchronized void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("Shutting down DiscoveryClient ...");
            /*
                关闭监听器
             */
            if (statusChangeListener != null && applicationInfoManager != null) {
                applicationInfoManager.unregisterStatusChangeListener(statusChangeListener.getId());
            }

            cancelScheduledTasks();

            /*


             */
            // If APPINFO was registered
            if (applicationInfoManager != null
                    && clientConfig.shouldRegisterWithEureka()
                    && clientConfig.shouldUnregisterOnShutdown()) {
                applicationInfoManager.setInstanceStatus(InstanceStatus.DOWN);
                unregister();
            }
            /*
                关闭网络组建
             */
            if (eurekaTransport != null) {
                eurekaTransport.shutdown();
            }
            /*
                关闭所有监听器
             */
            heartbeatStalenessMonitor.shutdown();
            registryStalenessMonitor.shutdown();

            Monitors.unregisterObject(this);

            logger.info("Completed shut down of DiscoveryClient");
        }
    }

    /**
     * unregister w/ the eureka service.
     */
    void unregister() {
        // It can be null if shouldRegisterWithEureka == false
        if(eurekaTransport != null && eurekaTransport.registrationClient != null) {
            try {
                logger.info("Unregistering ...");
                EurekaHttpResponse<Void> httpResponse = eurekaTransport.registrationClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());
                logger.info(PREFIX + "{} - deregister  status: {}", appPathIdentifier, httpResponse.getStatusCode());
            } catch (Exception e) {
                logger.error(PREFIX + "{} - de-registration failed{}", appPathIdentifier, e.getMessage(), e);
            }
        }
    }

    /**
     * Fetches the registry information.
     *
     * <p>
     * This method tries to get only deltas after the first fetch unless there
     * is an issue in reconciling eureka server and client registry information.
     * </p>
     *
     * @param forceFullRegistryFetch Forces a full registry fetch.
     *
     * @return true if the registry was fetched
     *
     *
     * 全量抓取注册表,EurekaClient第一次启动的时候,必须从EurekaServer端一次性抓取全量的注册表的信息过来,
     * 在本地进行缓存,后面的话,每隔30秒从EurekaServer抓取增量的注册表信息,跟本地缓存进行合并,步骤为:
     * 1. Applications 本地缓存
     *  1). 先获取本地的Applications缓存
     *  2). 包含了它自己的所有InstanceInfo服务信息
     * 2. 调用jersey抓取注册表信息
     * 3. 抓取成功,设置到localRegionApps注册表之中
     */
    private boolean fetchRegistry(boolean forceFullRegistryFetch) {
        Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

        try {
            // If the delta is disabled or if it is the first time, get all
            // applications
            Applications applications = getApplications();

            //第一次全量抓取肯定符合这块逻辑
            if (clientConfig.shouldDisableDelta()
                    || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                    || forceFullRegistryFetch  //这个是false
                    || (applications == null)  //本地是否存在
                    || (applications.getRegisteredApplications().size() == 0)
                    || (applications.getVersion() == -1)) //Client application does not have latest library supporting delta
            {
                logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
                logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
                logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
                logger.info("Application is null : {}", (applications == null));
                logger.info("Registered Applications size is zero : {}",
                        (applications.getRegisteredApplications().size() == 0));
                logger.info("Application version is -1: {}", (applications.getVersion() == -1));
                getAndStoreFullRegistry();
            } else {
                //这里是增量抓取
                getAndUpdateDelta(applications);
            }
            applications.setAppsHashCode(applications.getReconcileHashCode());
            logTotalInstances();
        } catch (Throwable e) {
            logger.info(PREFIX + "{} - was unable to refresh its cache! This periodic background refresh will be retried in {} seconds. status = {} stacktrace = {}",
                    appPathIdentifier, clientConfig.getRegistryFetchIntervalSeconds(), e.getMessage(), ExceptionUtils.getStackTrace(e));
            return false;
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }

        // Notify about cache refresh before updating the instance remote status
        onCacheRefreshed();

        // Update remote status based on refreshed data held in the cache
        updateInstanceRemoteStatus();

        // registry was fetched successfully, so return true
        return true;
    }

    private synchronized void updateInstanceRemoteStatus() {
        // Determine this instance's status for this app and set to UNKNOWN if not found
        InstanceInfo.InstanceStatus currentRemoteInstanceStatus = null;
        if (instanceInfo.getAppName() != null) {
            Application app = getApplication(instanceInfo.getAppName());
            if (app != null) {
                InstanceInfo remoteInstanceInfo = app.getByInstanceId(instanceInfo.getId());
                if (remoteInstanceInfo != null) {
                    currentRemoteInstanceStatus = remoteInstanceInfo.getStatus();
                }
            }
        }
        if (currentRemoteInstanceStatus == null) {
            currentRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
        }

        // Notify if status changed
        if (lastRemoteInstanceStatus != currentRemoteInstanceStatus) {
            onRemoteStatusChanged(lastRemoteInstanceStatus, currentRemoteInstanceStatus);
            lastRemoteInstanceStatus = currentRemoteInstanceStatus;
        }
    }

    /**
     * @return Return he current instance status as seen on the Eureka server.
     */
    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return lastRemoteInstanceStatus;
    }

    private String getReconcileHashCode(Applications applications) {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        if (isFetchingRemoteRegionRegistries()) {
            for (Applications remoteApp : remoteRegionVsApps.values()) {
                remoteApp.populateInstanceCountMap(instanceCountMap);
            }
        }
        applications.populateInstanceCountMap(instanceCountMap);
        return Applications.getReconcileHashCode(instanceCountMap);
    }

    /**
     * Gets the full registry information from the eureka server and stores it locally.
     * When applying the full registry, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     * @return the full registry information.
     * @throws Throwable
     *             on error.
     */
    private void getAndStoreFullRegistry() throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        logger.info("Getting all instance registry info from the eureka server");

        Applications apps = null;
        // 第一次会走这个方法 eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                // 去applications 的resource进行抓取
                // 代表发送的是get请求
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            apps = httpResponse.getEntity();
        }
        logger.info("The response status is {}", httpResponse.getStatusCode());

        if (apps == null) {
            //为null则代表抓取失败
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            // 抓取成功,设置到localRegionApps注册表之中
            localRegionApps.set(this.filterAndShuffle(apps));
            logger.debug("Got full registry with apps hashcode {}", apps.getAppsHashCode());
        } else {
            logger.warn("Not updating applications as another thread is updating it already");
        }
    }

    /**
     * Get the delta registry information from the eureka server and update it locally.
     * When applying the delta, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically try to: update application with the delta and get reconcileHashCode
     *   abort entire processing otherwise
     *   do reconciliation if reconcileHashCode clash
     * fi
     *
     * @return the client response
     * @throws Throwable on error
     *
     *
     * 增量抓取的逻辑
     * 1. 抓取
     *  1). 走的是apps/delta这个方法
     *  2). remoteRegionsRef.get()会返回自己的一个String的数组,然后传递给EurekaServer
     *  3). EurekaServer会计算出传递过来的一个hashkey值
     *  4). 根据hashkey值去只读缓存和读写缓存中拉取
     *  5). 然后返回给EurekaClient,同时包含EurekaServer全量hash表的值
     * 2. 如果为null
     *  1). 会再走一次全量抓取逻辑,拿到全量的注册表
     * 3. 如果不是null
     *  1). 先加锁
     *  2). 更新增量的注册表,根据状态走不通的逻辑
     *  3). 如果是新增的,则进行ADD逻辑
     *  4). 如果是删除的,就在本地注册表进行删除
     *  5). 如果是更新,则修改本地注册表
     *
     */
    private void getAndUpdateDelta(Applications applications) throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        Applications delta = null;
        EurekaHttpResponse<Applications> httpResponse = eurekaTransport.queryClient.getDelta(remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            delta = httpResponse.getEntity();
        }

        if (delta == null) {
            logger.warn("The server does not allow the delta revision to be applied because it is not safe. "
                    + "Hence got the full registry.");
            //如果不存在,再走一遍全量抓取的逻辑
            getAndStoreFullRegistry();
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            logger.debug("Got delta update with apps hashcode {}", delta.getAppsHashCode());
            String reconcileHashCode = "";
            if (fetchRegistryUpdateLock.tryLock()) {
                try {
                    updateDelta(delta);
                    reconcileHashCode = getReconcileHashCode(applications);
                } finally {
                    fetchRegistryUpdateLock.unlock();
                }
            } else {
                logger.warn("Cannot acquire update lock, aborting getAndUpdateDelta");
            }
            // There is a diff in number of instances for some reason
            if (!reconcileHashCode.equals(delta.getAppsHashCode()) || clientConfig.shouldLogDeltaDiff()) {
                reconcileAndLogDifference(delta, reconcileHashCode);  // this makes a remoteCall
            }
        } else {
            logger.warn("Not updating application delta as another thread is updating it already");
            logger.debug("Ignoring delta update with apps hashcode {}, as another thread is updating it already", delta.getAppsHashCode());
        }
    }

    /**
     * Logs the total number of non-filtered instances stored locally.
     */
    private void logTotalInstances() {
        if (logger.isDebugEnabled()) {
            int totInstances = 0;
            for (Application application : getApplications().getRegisteredApplications()) {
                totInstances += application.getInstancesAsIsFromEureka().size();
            }
            logger.debug("The total number of all instances in the client now is {}", totInstances);
        }
    }

    /**
     * Reconcile the eureka server and client registry information and logs the differences if any.
     * When reconciling, the following flow is observed:
     *
     * make a remote call to the server for the full registry
     * calculate and log differences
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     * @param delta
     *            the last delta registry information received from the eureka
     *            server.
     * @param reconcileHashCode
     *            the hashcode generated by the server for reconciliation.
     * @return ClientResponse the HTTP response object.
     * @throws Throwable
     *             on any error.
     */
    private void reconcileAndLogDifference(Applications delta, String reconcileHashCode) throws Throwable {
        logger.debug("The Reconcile hashcodes do not match, client : {}, server : {}. Getting the full registry",
                reconcileHashCode, delta.getAppsHashCode());

        RECONCILE_HASH_CODES_MISMATCH.increment();

        long currentUpdateGeneration = fetchRegistryGeneration.get();

        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        Applications serverApps = httpResponse.getEntity();

        if (serverApps == null) {
            logger.warn("Cannot fetch full registry from the server; reconciliation failure");
            return;
        }

        if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            localRegionApps.set(this.filterAndShuffle(serverApps));
            getApplications().setVersion(delta.getVersion());
            logger.debug(
                    "The Reconcile hashcodes after complete sync up, client : {}, server : {}.",
                    getApplications().getReconcileHashCode(),
                    delta.getAppsHashCode());
        } else {
            logger.warn("Not setting the applications map as another thread has advanced the update generation");
        }
    }

    /**
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta
     *            the delta information received from eureka server in the last
     *            poll cycle.
     *
     *             更新增量
     */
   private void updateDelta(Applications delta) {
        int deltaCount = 0;
        for (Application app : delta.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                Applications applications = getApplications();
                String instanceRegion = instanceRegionChecker.getInstanceRegion(instance);
                if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                    Applications remoteApps = remoteRegionVsApps.get(instanceRegion);
                    if (null == remoteApps) {
                        remoteApps = new Applications();
                        remoteRegionVsApps.put(instanceRegion, remoteApps);
                    }
                    applications = remoteApps;
                }

                ++deltaCount;
                //如果是增加
                if (ActionType.ADDED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps in region {}", instance.getId(), instanceRegion);
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ", instance.getId());

                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);

                } else if (ActionType.DELETED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp != null) {
                        logger.debug("Deleted instance {} to the existing apps ", instance.getId());
                        existingApp.removeInstance(instance);
                        /*
                         * We find all instance list from application(The status of instance status is not only the status is UP but also other status)
                         * if instance list is empty, we remove the application.
                         */
                        if (existingApp.getInstancesAsIsFromEureka().isEmpty()) {
                            applications.removeApplication(existingApp);
                        }
                    }
                }
            }
        }
        logger.debug("The total number of instances fetched by the delta processor : {}", deltaCount);

        getApplications().setVersion(delta.getVersion());
        getApplications().shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());

        for (Applications applications : remoteRegionVsApps.values()) {
            applications.setVersion(delta.getVersion());
            applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
        }
    }


    /** description: Initializes all scheduled tasks
     * 初始化抓取注册表的调度任务 , 注册一个定时任务,按照抓取的间隔,默认是30s, 去执行一个CacheRefreshThread
     * 然后放到调度任务中去
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/3/17 20:42
     */
    private void initScheduledTasks() {

        if (clientConfig.shouldFetchRegistry()) {
            // registry cache refresh timer
            /*
             * 拿到缓存刷新的线程  , 默认是30s
             */
            int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
            int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
            cacheRefreshTask = new TimedSupervisorTask(
                    "cacheRefresh",
                    //任务
                    scheduler,
                    cacheRefreshExecutor,
                    //抓取间隔
                    registryFetchIntervalSeconds,
                    TimeUnit.SECONDS,
                    expBackOffBound,
                    //执行的是这个方法的run
                    new CacheRefreshThread()
            );
            scheduler.schedule(
                    cacheRefreshTask,
                    registryFetchIntervalSeconds, TimeUnit.SECONDS);
        }

        if (clientConfig.shouldRegisterWithEureka()) {
            int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
            int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
            logger.info("Starting heartbeat executor: " + "renew interval is: {}", renewalIntervalInSecs);

            // Heartbeat timer
            /*
                心跳机制的定时任务线程
                1. 初始化 heartbeatTask 调度任务
                2. 默认每隔30S执行HeartbeatThread的心跳逻辑
                3. 走/v2/apps/appName/id1的Get方法拿到一个ApplicationResource服务相关的Resource调用它的GET/{id}返回一个InstanceResource的Resource
                4. 通过InstanceResource走一个PUT请求
                5. EurekaServer走检查逻辑
                    1). 走AbstractInstanceRegistry的renew方法
                    2). 根据AppName去注册表中拿到一个gMap
                    3). 根据InstanceInfo的ID去拿到一个Lease
                    4). 检查数据
                6. EurekaServer走续约逻辑
                    1). 把这个lease的最后更新时间修改为 now()+DEFAULT_LEASE_DURATION(默认是90s)
             */
            heartbeatTask = new TimedSupervisorTask(
                    "heartbeat",
                    scheduler,
                    heartbeatExecutor,

                    // 这个默认值是30  即每隔30s进行设置
                    renewalIntervalInSecs,
                    TimeUnit.SECONDS,
                    expBackOffBound,

                    // 执行心跳的逻辑
                    new HeartbeatThread()
            );
            scheduler.schedule(
                    heartbeatTask,
                    renewalIntervalInSecs, TimeUnit.SECONDS);

            // InstanceInfo replicator
            /*
             * 副本机制,复制的概念
             * 本意 : replicate 即将数据放到多个副本上去
             * 这里的意思: 将EurekaClient的一个副本放到EurekaServer中去
             */
            instanceInfoReplicator = new InstanceInfoReplicator(
                    this,
                    instanceInfo,
                    clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                    2); // burstSize

            statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "statusChangeListener";
                }

                @Override
                public void notify(StatusChangeEvent statusChangeEvent) {
                    logger.info("Saw local status change event {}", statusChangeEvent);
                    instanceInfoReplicator.onDemandUpdate();
                }
            };
            /*
             * 如果配置了监听,则会配置监听器 , 服务状态实例变更的监听器
             */
            if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                applicationInfoManager.registerStatusChangeListener(statusChangeListener);
            }
            /*
             * 服务传播的组件
             */
            instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
        } else {
            logger.info("Not registering with Eureka server per configuration");
        }
    }

    private void cancelScheduledTasks() {
        if (instanceInfoReplicator != null) {
            instanceInfoReplicator.stop();
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (cacheRefreshExecutor != null) {
            cacheRefreshExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (cacheRefreshTask != null) {
            cacheRefreshTask.cancel();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     *
     * @param instanceZone The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromDNS(clientConfig, instanceZone, preferSameZone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     */
    @Deprecated
    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return EndpointUtils.getDiscoveryServiceUrls(clientConfig, zone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of EC2 URLs given the zone name.
     *
     * @param dnsName The dns name of the zone-specific CNAME
     * @param type CNAME or EIP that needs to be retrieved
     * @return The list of EC2 URLs associated with the dns name
     */
    @Deprecated
    public static Set<String> getEC2DiscoveryUrlsFromZone(String dnsName,
                                                          EndpointUtils.DiscoveryUrlType type) {
        return EndpointUtils.getEC2DiscoveryUrlsFromZone(dnsName, type);
    }

    /**
     * Refresh the current local instanceInfo. Note that after a valid refresh where changes are observed, the
     * isDirty flag on the instanceInfo is set to true
     */
    void refreshInstanceInfo() {
        /*
         * 1.拿到主机名和地址
         * 2.如果主机名和地址发生了改变
         * 3.关键配置是否改变,改变了的情况下,刷新一下
         */
        applicationInfoManager.refreshDataCenterInfoIfRequired();
        /*
            刷新服务实例的状态,然后设置服务实例的状态到applicationInfoManager中
         */
        applicationInfoManager.refreshLeaseInfoIfRequired();

        InstanceStatus status;
        try {
            status = getHealthCheckHandler().getStatus(instanceInfo.getStatus());
        } catch (Exception e) {
            logger.warn("Exception from healthcheckHandler.getStatus, setting status to DOWN", e);
            status = InstanceStatus.DOWN;
        }

        if (null != status) {
            applicationInfoManager.setInstanceStatus(status);
        }
    }

    /**
     * The heartbeat task that renews the lease in the given intervals.
     */
    private class HeartbeatThread implements Runnable {

        public void run() {
            if (renew()) {
                lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
            }
        }
    }

    @VisibleForTesting
    InstanceInfoReplicator getInstanceInfoReplicator() {
        return instanceInfoReplicator;
    }

    @VisibleForTesting
    InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        HealthCheckHandler healthCheckHandler = this.healthCheckHandlerRef.get();
        if (healthCheckHandler == null) {
            if (null != healthCheckHandlerProvider) {
                healthCheckHandler = healthCheckHandlerProvider.get();
            } else if (null != healthCheckCallbackProvider) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(healthCheckCallbackProvider.get());
            }

            if (null == healthCheckHandler) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(null);
            }
            this.healthCheckHandlerRef.compareAndSet(null, healthCheckHandler);
        }

        return this.healthCheckHandlerRef.get();
    }

    /**
     * The task that fetches the registry information at specified intervals.
     *
     */
    class CacheRefreshThread implements Runnable {
        public void run() {
            refreshRegistry();
        }
    }
    /*
      这里是刷新注册表信息
     */
    @VisibleForTesting
    void refreshRegistry() {
        try {
            boolean isFetchingRemoteRegionRegistries = isFetchingRemoteRegionRegistries();

            boolean remoteRegionsModified = false;
            // This makes sure that a dynamic change to remote regions to fetch is honored.
            String latestRemoteRegions = clientConfig.fetchRegistryForRemoteRegions();
            if (null != latestRemoteRegions) {
                String currentRemoteRegions = remoteRegionsToFetch.get();
                if (!latestRemoteRegions.equals(currentRemoteRegions)) {
                    // Both remoteRegionsToFetch and AzToRegionMapper.regionsToFetch need to be in sync
                    synchronized (instanceRegionChecker.getAzToRegionMapper()) {
                        if (remoteRegionsToFetch.compareAndSet(currentRemoteRegions, latestRemoteRegions)) {
                            String[] remoteRegions = latestRemoteRegions.split(",");
                            remoteRegionsRef.set(remoteRegions);
                            instanceRegionChecker.getAzToRegionMapper().setRegionsToFetch(remoteRegions);
                            remoteRegionsModified = true;
                        } else {
                            logger.info("Remote regions to fetch modified concurrently," +
                                    " ignoring change from {} to {}", currentRemoteRegions, latestRemoteRegions);
                        }
                    }
                } else {
                    // Just refresh mapping to reflect any DNS/Property change
                    instanceRegionChecker.getAzToRegionMapper().refreshMapping();
                }
            }

            boolean success = fetchRegistry(remoteRegionsModified);
            if (success) {
                registrySize = localRegionApps.get().size();
                lastSuccessfulRegistryFetchTimestamp = System.currentTimeMillis();
            }

            if (logger.isDebugEnabled()) {
                StringBuilder allAppsHashCodes = new StringBuilder();
                allAppsHashCodes.append("Local region apps hashcode: ");
                allAppsHashCodes.append(localRegionApps.get().getAppsHashCode());
                allAppsHashCodes.append(", is fetching remote regions? ");
                allAppsHashCodes.append(isFetchingRemoteRegionRegistries);
                for (Map.Entry<String, Applications> entry : remoteRegionVsApps.entrySet()) {
                    allAppsHashCodes.append(", Remote region: ");
                    allAppsHashCodes.append(entry.getKey());
                    allAppsHashCodes.append(" , apps hashcode: ");
                    allAppsHashCodes.append(entry.getValue().getAppsHashCode());
                }
                logger.debug("Completed cache refresh task for discovery. All Apps hash code is {} ",
                        allAppsHashCodes);
            }
        } catch (Throwable e) {
            logger.error("Cannot fetch registry from server", e);
        }
    }

    /**
     * Fetch the registry information from back up registry if all eureka server
     * urls are unreachable.
     *
     * @return true if the registry was fetched
     */
    private boolean fetchRegistryFromBackup() {
        try {
            @SuppressWarnings("deprecation")
            BackupRegistry backupRegistryInstance = newBackupRegistryInstance();
            if (null == backupRegistryInstance) { // backward compatibility with the old protected method, in case it is being used.
                backupRegistryInstance = backupRegistryProvider.get();
            }

            if (null != backupRegistryInstance) {
                Applications apps = null;
                if (isFetchingRemoteRegionRegistries()) {
                    String remoteRegionsStr = remoteRegionsToFetch.get();
                    if (null != remoteRegionsStr) {
                        apps = backupRegistryInstance.fetchRegistry(remoteRegionsStr.split(","));
                    }
                } else {
                    apps = backupRegistryInstance.fetchRegistry();
                }
                if (apps != null) {
                    final Applications applications = this.filterAndShuffle(apps);
                    applications.setAppsHashCode(applications.getReconcileHashCode());
                    localRegionApps.set(applications);
                    logTotalInstances();
                    logger.info("Fetched registry successfully from the backup");
                    return true;
                }
            } else {
                logger.warn("No backup registry instance defined & unable to find any discovery servers.");
            }
        } catch (Throwable e) {
            logger.warn("Cannot fetch applications from apps although backup registry was specified", e);
        }
        return false;
    }

    /**
     * @deprecated Use injection to provide {@link BackupRegistry} implementation.
     */
    @Deprecated
    @Nullable
    protected BackupRegistry newBackupRegistryInstance()
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return null;
    }

    /**
     * Gets the <em>applications</em> after filtering the applications for
     * instances with only UP states and shuffling them.
     *
     * <p>
     * The filtering depends on the option specified by the configuration
     * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}. Shuffling helps
     * in randomizing the applications list there by avoiding the same instances
     * receiving traffic during start ups.
     * </p>
     *
     * @param apps
     *            The applications that needs to be filtered and shuffled.
     * @return The applications after the filter and the shuffle.
     * 缓存在自己本地的方法 , 在remoteRegionVsApps
     */
    private Applications filterAndShuffle(Applications apps) {
        if (apps != null) {
            if (isFetchingRemoteRegionRegistries()) {
                //全量拉取走这里
                Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<String, Applications>();
                apps.shuffleAndIndexInstances(remoteRegionVsApps, clientConfig, instanceRegionChecker);
                for (Applications applications : remoteRegionVsApps.values()) {
                    applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
                }
                this.remoteRegionVsApps = remoteRegionVsApps;
            } else {
                apps.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
            }
        }
        return apps;
    }

    private boolean isFetchingRemoteRegionRegistries() {
        return null != remoteRegionsToFetch.get();
    }

    /**
     * Invoked when the remote status of this client has changed.
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * @param oldStatus the previous remote {@link InstanceStatus}
     * @param newStatus the new remote {@link InstanceStatus}
     */
    protected void onRemoteStatusChanged(InstanceInfo.InstanceStatus oldStatus, InstanceInfo.InstanceStatus newStatus) {
        fireEvent(new StatusChangeEvent(oldStatus, newStatus));
    }


    /**
     * Invoked every time the local registry cache is refreshed (whether changes have
     * been detected or not).
     *
     * Subclasses may override this method to implement custom behavior if needed.
     */
    protected void onCacheRefreshed() {
        fireEvent(new CacheRefreshedEvent());
    }

    /**
     * Send the given event on the EventBus if one is available
     *
     * @param event the event to send on the eventBus
     */
    protected void fireEvent(final EurekaEvent event) {
        for (EurekaEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.info("Event {} throw an exception for listener {}", event, listener, e.getMessage());
            }
        }
    }


    /**
     * @deprecated see {@link com.netflix.appinfo.InstanceInfo#getZone(String[], com.netflix.appinfo.InstanceInfo)}
     *
     * Get the zone that a particular instance is in.
     *
     * @param myInfo
     *            - The InstanceInfo object of the instance.
     * @return - The zone in which the particular instance belongs to.
     */
    @Deprecated
    public static String getZone(InstanceInfo myInfo) {
        String[] availZones = staticClientConfig.getAvailabilityZones(staticClientConfig.getRegion());
        return InstanceInfo.getZone(availZones, myInfo);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the region that this particular instance is in.
     *
     * @return - The region in which the particular instance belongs to.
     */
    @Deprecated
    public static String getRegion() {
        String region = staticClientConfig.getRegion();
        if (region == null) {
            region = "default";
        }
        region = region.trim().toLowerCase();
        return region;
    }

    /**
     * @deprecated use {@link #getServiceUrlsFromConfig(String, boolean)} instead.
     */
    @Deprecated
    public static List<String> getEurekaServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(staticClientConfig, instanceZone, preferSameZone);
    }

    public long getLastSuccessfulHeartbeatTimePeriod() {
        return lastSuccessfulHeartbeatTimestamp < 0
                ? lastSuccessfulHeartbeatTimestamp
                : System.currentTimeMillis() - lastSuccessfulHeartbeatTimestamp;
    }

    public long getLastSuccessfulRegistryFetchTimePeriod() {
        return lastSuccessfulRegistryFetchTimestamp < 0
                ? lastSuccessfulRegistryFetchTimestamp
                : System.currentTimeMillis() - lastSuccessfulRegistryFetchTimestamp;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRATION_PREFIX + "lastSuccessfulHeartbeatTimePeriod",
            description = "How much time has passed from last successful heartbeat", type = DataSourceType.GAUGE)
    private long getLastSuccessfulHeartbeatTimePeriodInternal() {
        final long delay = (!clientConfig.shouldRegisterWithEureka() || isShutdown.get())
            ? 0
            : getLastSuccessfulHeartbeatTimePeriod();

        heartbeatStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    // for metrics only
    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "lastSuccessfulRegistryFetchTimePeriod",
            description = "How much time has passed from last successful local registry update", type = DataSourceType.GAUGE)
    private long getLastSuccessfulRegistryFetchTimePeriodInternal() {
        final long delay = (!clientConfig.shouldFetchRegistry() || isShutdown.get())
            ? 0
            : getLastSuccessfulRegistryFetchTimePeriod();

        registryStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "localRegistrySize",
            description = "Count of instances in the local registry", type = DataSourceType.GAUGE)
    public int localRegistrySize() {
        return registrySize;
    }


    private long computeStalenessMonitorDelay(long delay) {
        if (delay < 0) {
            return System.currentTimeMillis() - initTimestampMs;
        } else {
            return delay;
        }
    }

    /**
     * Gets stats for the DiscoveryClient.
     *
     * @return The DiscoveryClientStats instance.
     */
    public Stats getStats() {
        return stats;
    }

    /**
     * Stats is used to track useful attributes of the DiscoveryClient. It includes helpers that can aid
     * debugging and log analysis.
     */
    public class Stats {

        private Stats() {}

        public int initLocalRegistrySize() {
            return initRegistrySize;
        }

        public long initTimestampMs() {
            return initTimestampMs;
        }

        public int localRegistrySize() {
            return registrySize;
        }

        public long lastSuccessfulRegistryFetchTimestampMs() {
            return lastSuccessfulRegistryFetchTimestamp;
        }

        public long lastSuccessfulHeartbeatTimestampMs() {
            return lastSuccessfulHeartbeatTimestamp;
        }

        /**
         * Used to determine if the Discovery client's first attempt to fetch from the service registry succeeded with
         * non-empty results.
         *
         * @return true if succeeded, failed otherwise
         */
        public boolean initSucceeded() {
            return initLocalRegistrySize() > 0 && initTimestampMs() > 0;
        }

    }

}
