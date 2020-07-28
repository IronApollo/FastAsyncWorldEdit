package com.boydti.fawe;

import com.boydti.fawe.beta.implementation.queue.QueueHandler;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.brush.visualization.VisualQueue;
import com.boydti.fawe.util.CachedTextureUtil;
import com.boydti.fawe.util.CleanTextureUtil;
import com.boydti.fawe.util.FaweTimer;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.MemUtil;
import com.boydti.fawe.util.RandomTextureUtil;
import com.boydti.fawe.util.TaskManager;
import com.boydti.fawe.util.TextureUtil;
import com.boydti.fawe.util.WEManager;
import com.github.luben.zstd.util.Native;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.management.InstanceAlreadyExistsException;
import javax.management.NotificationEmitter;

/**
 * [ WorldEdit action].
 * |
 * \|/
 * [ EditSession ] - The change is processed (area restrictions, change limit, block type)
 * |
 * \|/
 * [Block change] - A block change from some location
 * |
 * \|/
 * [ Set Queue ] - The SetQueue manages the implementation specific queue
 * |
 * \|/
 * [ Fawe Queue] - A queue of chunks - check if the queue has the chunk for a change
 * |
 * \|/
 * [ Fawe Chunk Implementation ] - Otherwise create a new FaweChunk object which is a wrapper around the Chunk object
 * |
 * \|/
 * [ Execution ] - When done, the queue then sets the blocks for the chunk, performs lighting updates and sends the chunk packet to the clients
 *
 * <p>
 * Why it's faster:
 * </p>
 * <ul>
 * <li>The chunk is modified directly rather than through the API.
 * <ul>
 *     <li>Removes some overhead, and means some processing can be done async</li>
 * </ul></li>
 * <li> Lighting updates are performed on the chunk level rather than for every block <ul>
 *     <li>e.g., A blob of stone: only the visible blocks need to have the lighting calculated</li>
 * </ul> </li>
 * <li> Block changes are sent with a chunk packet.
 * <ul>
 *     <li>A chunk packet is generally quicker to create and smaller for large world edits</li>
 * </ul>
 * </li>
 * <li> No physics updates.
 * <ul>
 * <li>Physics updates are slow, and are usually performed on each block</li>
 * </ul></li>
 * <li> Block data shortcuts <ul>
 * <li>Some known blocks don't need to have the data set or accessed (e.g., air is never going to have data)</li>
 * </ul></li>
 * <li> Remove redundant extents
 * <ul>
 * <li>Up to 11 layers of extents can be removed</li>
 * </ul>
 * </li>
 * <li> History bypassing.
 * <ul>
 * <li>FastMode bypasses history and means blocks in the world don't need to be checked and recorded</li>
 * </ul>
 * </li>
 * </ul>
 */
public class Fawe {

    private static final Logger log = LoggerFactory.getLogger(Fawe.class);

    /**
     * The FAWE instance.
     */
    private static Fawe INSTANCE;

    //TPS Timer
    private final FaweTimer timer;
    private FaweVersion version;
    private VisualQueue visualQueue;
    private TextureUtil textures;
    // TODO: Ping @MattBDev to reimplement 2020-02-04
    //    private DefaultTransformParser transformParser;

    private QueueHandler queueHandler;

    /**
     * Get the implementation specific class.
     */
    @SuppressWarnings("unchecked")
    public static <T extends IFawe> T imp() {
        return INSTANCE != null ? (T) INSTANCE.imp : null;
    }

    /**
     * Get the implementation independent class.
     */
    public static Fawe get() {
        return INSTANCE;
    }

    /**
     * Setup Fawe.
     */
    public static void set(final IFawe implementation) throws InstanceAlreadyExistsException, IllegalArgumentException {
        if (INSTANCE != null) {
            throw new InstanceAlreadyExistsException("FAWE has already been initialized with: " + INSTANCE.imp);
        }
        if (implementation == null) {
            throw new IllegalArgumentException("Implementation may not be null.");
        }
        INSTANCE = new Fawe(implementation);
    }

    public static void debugPlain(String s) {
        if (INSTANCE != null) {
            INSTANCE.imp.debug(s);
        } else {
            System.out.println(s);
        }
    }

    /**
     * Write something to the console.
     */
    public static void debug(String s) {
        Actor actor = Request.request().getActor();
        if (actor != null && actor.isPlayer()) {
            actor.printInfo(TextComponent.of(s));
            return;
        }
        debugPlain(s);
    }

    /**
     * Write something to the console.
     *
     * @param c The Component to be printed
     */
    public static void debug(Component c) {
        Actor actor = Request.request().getActor();
        if (actor != null && actor.isPlayer()) {
            actor.printDebug(c);
            return;
        }
        debugPlain(c.toString());
    }

    private final IFawe imp;
    private Thread thread;

    private Fawe(final IFawe implementation) {
        INSTANCE = this;
        this.imp = implementation;
        this.thread = Thread.currentThread();
        /*
         * Implementation dependent stuff
         */
        this.setupConfigs();
        TaskManager.IMP = this.imp.getTaskManager();

        TaskManager.IMP.async(() -> {
            MainUtil.deleteOlder(MainUtil.getFile(imp.getDirectory(), Settings.IMP.PATHS.HISTORY), TimeUnit.DAYS.toMillis(Settings.IMP.HISTORY.DELETE_AFTER_DAYS), false);
            MainUtil.deleteOlder(MainUtil.getFile(imp.getDirectory(), Settings.IMP.PATHS.CLIPBOARD), TimeUnit.DAYS.toMillis(Settings.IMP.CLIPBOARD.DELETE_AFTER_DAYS), false);
        });

        /*
         * Instance independent stuff
         */
        this.setupMemoryListener();
        this.timer = new FaweTimer();

        // Delayed worldedit setup
        TaskManager.IMP.later(() -> {
            try {
                // TODO: Ping @MattBDev to reimplement 2020-02-04
                //                transformParser = new DefaultTransformParser(getWorldEdit());
                visualQueue = new VisualQueue(3);
                WEManager.IMP.managers.addAll(Fawe.this.imp.getMaskManagers());
            } catch (Throwable ignored) {
            }
        }, 0);

        TaskManager.IMP.repeat(timer, 1);
    }

    public void onDisable() {
    }

    public QueueHandler getQueueHandler() {
        if (queueHandler == null) {
            synchronized (this) {
                if (queueHandler == null) {
                    queueHandler = imp.getQueueHandler();
                }
            }
        }
        return queueHandler;
    }

    // TODO: Ping @MattBDev to reimplement 2020-02-04
    //    public DefaultTransformParser getTransformParser() {
    //        return transformParser;
    //    }

    public TextureUtil getCachedTextureUtil(boolean randomize, int min, int max) {
        // TODO NOT IMPLEMENTED - optimize this by caching the default true/0/100 texture util
        TextureUtil tu = getTextureUtil();
        try {
            tu = min == 0 && max == 100 ? tu : new CleanTextureUtil(tu, min, max);
            tu = randomize ? new RandomTextureUtil(tu) : new CachedTextureUtil(tu);
        } catch (FileNotFoundException neverHappens) {
            neverHappens.printStackTrace();
        }
        return tu;
    }

    public TextureUtil getTextureUtil() {
        TextureUtil tmp = textures;
        if (tmp == null) {
            synchronized (this) {
                tmp = textures;
                if (tmp == null) {
                    try {
                        textures = tmp = new TextureUtil();
                        tmp.loadModTextures();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return tmp;
    }

    /**
     * The FaweTimer is a useful class for monitoring TPS.
     */
    public FaweTimer getTimer() {
        return timer;
    }

    /**
     * The visual queue is used to queue visualizations.
     */
    public VisualQueue getVisualQueue() {
        return visualQueue;
    }

    /**
     * The FAWE version.
     * - Unofficial jars may be lacking version information
     */
    @Nullable
    public FaweVersion getVersion() {
        return version;
    }

    public double getTPS() {
        return timer.getTPS();
    }

    public void setupConfigs() {
        MainUtil.copyFile(MainUtil.getJarFile(), "lang/strings.json", null);
        // Setting up config.yml
        File file = new File(this.imp.getDirectory(), "config.yml");
        Settings.IMP.PLATFORM = imp.getPlatform().replace("\"", "");
        try (InputStream stream = getClass().getResourceAsStream(File.separator + "fawe.properties");
             BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
            String versionString = br.readLine();
            String commitString = br.readLine();
            String dateString = br.readLine();
            br.close();
            this.version = FaweVersion.tryParse(versionString, commitString, dateString);
            Settings.IMP.DATE = new Date(100 + version.year, version.month, version.day).toGMTString();
            Settings.IMP.BUILD = "https://ci.athion.net/job/FastAsyncWorldEdit-1.16/" + version.build;
            Settings.IMP.COMMIT = "https://github.com/IntellectualSites/FastAsyncWorldEdit/commit/" + Integer.toHexString(version.hash);
        } catch (Throwable ignored) {
        }
        try {
            Settings.IMP.reload(file);
        } catch (Throwable e) {
            debug("====== Failed to load config ======");
            debug("Please validate your yaml files:");
            e.printStackTrace();
            debug("====================================");
        }
    }


    public WorldEdit getWorldEdit() {
        return WorldEdit.getInstance();
    }

    public static void setupInjector() {
        /*
         * Modify the sessions
         *  - EditSession supports a custom queue, and a lot of optimizations
         *  - LocalSession supports VirtualPlayers and undo on disk
         */
        if (!Settings.IMP.EXPERIMENTAL.DISABLE_NATIVES) {
            try {
                Native.load();
            } catch (Throwable e) {
                if (Settings.IMP.CLIPBOARD.COMPRESSION_LEVEL > 6 || Settings.IMP.HISTORY.COMPRESSION_LEVEL > 6) {
                    Settings.IMP.CLIPBOARD.COMPRESSION_LEVEL = Math.min(6, Settings.IMP.CLIPBOARD.COMPRESSION_LEVEL);
                    Settings.IMP.HISTORY.COMPRESSION_LEVEL = Math.min(6, Settings.IMP.HISTORY.COMPRESSION_LEVEL);
                    debug("====== ZSTD COMPRESSION BINDING NOT FOUND ======");
                    debug(e.getMessage());
                    debug("FAWE will work but won't compress data as much");
                    debug("===============================================");
                }
            }
            try {
                net.jpountz.util.Native.load();
            } catch (Throwable e) {
                e.printStackTrace();
                debug("====== LZ4 COMPRESSION BINDING NOT FOUND ======");
                debug(e.getMessage());
                debug("FAWE will work but compression will be slower");
                debug(" - Try updating your JVM / OS");
                debug(" - Report this issue if you cannot resolve it");
                debug("===============================================");
            }
        }

        // Check Base OS Arch for Mismatching Architectures
        boolean x86OS = System.getProperty("sun.arch.data.model").contains("32");
        boolean x86JVM = System.getProperty("os.arch").contains("32");
        if (x86OS != x86JVM) {
            debug("====== UPGRADE TO 64-BIT JAVA ======");
            debug("You are running 32-bit Java on a 64-bit machine");
            debug("====================================");
        }
    }

    private void setupMemoryListener() {
        if (Settings.IMP.MAX_MEMORY_PERCENT < 1 || Settings.IMP.MAX_MEMORY_PERCENT > 99) {
            return;
        }
        try {
            final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            final NotificationEmitter ne = (NotificationEmitter) memBean;

            ne.addNotificationListener((notification, handback) -> {
                final long heapSize = Runtime.getRuntime().totalMemory();
                final long heapMaxSize = Runtime.getRuntime().maxMemory();
                if (heapSize < heapMaxSize) {
                    return;
                }
                MemUtil.memoryLimitedTask();
            }, null, null);

            final List<MemoryPoolMXBean> memPools = ManagementFactory.getMemoryPoolMXBeans();
            for (final MemoryPoolMXBean mp : memPools) {
                if (mp.isUsageThresholdSupported()) {
                    final MemoryUsage mu = mp.getUsage();
                    final long max = mu.getMax();
                    if (max < 0) {
                        continue;
                    }
                    final long alert = (max * Settings.IMP.MAX_MEMORY_PERCENT) / 100;
                    mp.setUsageThreshold(alert);
                }
            }
        } catch (Throwable ignored) {
            debug("====== MEMORY LISTENER ERROR ======");
            debug("FAWE needs access to the JVM memory system:");
            debug(" - Change your Java security settings");
            debug(" - Disable this with `max-memory-percent: -1`");
            debug("===================================");
        }
    }

    /**
     * Get the main thread.
     */
    public Thread getMainThread() {
        return this.thread;
    }

    public static boolean isMainThread() {
        return INSTANCE == null || INSTANCE.thread == Thread.currentThread();
    }

    /**
     * Sets the main thread to the current thread.
     */
    public Thread setMainThread() {
        return this.thread = Thread.currentThread();
    }
}
