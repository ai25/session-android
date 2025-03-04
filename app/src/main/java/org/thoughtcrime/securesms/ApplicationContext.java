/* Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import static nl.komponents.kovenant.android.KovenantAndroid.startKovenant;
import static nl.komponents.kovenant.android.KovenantAndroid.stopKovenant;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.conscrypt.Conscrypt;
import org.session.libsession.avatars.AvatarHelper;
import org.session.libsession.database.MessageDataProvider;
import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.messaging.sending_receiving.pollers.ClosedGroupPollerV2;
import org.session.libsession.messaging.sending_receiving.pollers.Poller;
import org.session.libsession.snode.SnodeModule;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.ProfilePictureUtilities;
import org.session.libsession.utilities.SSKEnvironment;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.WindowDebouncer;
import org.session.libsession.utilities.dynamiclanguage.DynamicLanguageContextWrapper;
import org.session.libsession.utilities.dynamiclanguage.LocaleParser;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.ThreadUtils;
import org.signal.aesgcmprovider.AesGcmProvider;
import org.thoughtcrime.securesms.components.TypingStatusSender;
import org.thoughtcrime.securesms.crypto.KeyPairUtilities;
import org.thoughtcrime.securesms.database.JobDatabase;
import org.thoughtcrime.securesms.database.LokiAPIDatabase;
import org.thoughtcrime.securesms.database.Storage;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.dependencies.DatabaseModule;
import org.thoughtcrime.securesms.groups.OpenGroupManager;
import org.thoughtcrime.securesms.home.HomeActivity;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.JsonDataSerializer;
import org.thoughtcrime.securesms.jobs.FastJobStorage;
import org.thoughtcrime.securesms.jobs.JobManagerFactories;
import org.thoughtcrime.securesms.logging.AndroidLogger;
import org.thoughtcrime.securesms.logging.PersistentLogger;
import org.thoughtcrime.securesms.logging.UncaughtExceptionLogger;
import org.thoughtcrime.securesms.notifications.BackgroundPollWorker;
import org.thoughtcrime.securesms.notifications.DefaultMessageNotifier;
import org.thoughtcrime.securesms.notifications.FcmUtils;
import org.thoughtcrime.securesms.notifications.LokiPushNotificationManager;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.notifications.OptimizedMessageNotifier;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.service.UpdateApkRefreshListener;
import org.thoughtcrime.securesms.sskenvironment.ProfileManager;
import org.thoughtcrime.securesms.sskenvironment.ReadReceiptManager;
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository;
import org.thoughtcrime.securesms.util.Broadcaster;
import org.thoughtcrime.securesms.util.UiModeUtilities;
import org.thoughtcrime.securesms.util.dynamiclanguage.LocaleParseHelper;
import org.thoughtcrime.securesms.webrtc.CallMessageProcessor;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.Security;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;

import javax.inject.Inject;

import dagger.hilt.EntryPoints;
import dagger.hilt.android.HiltAndroidApp;
import kotlin.Unit;
import kotlinx.coroutines.Job;
import network.loki.messenger.BuildConfig;

/**
 * Will be called once when the TextSecure process is created.
 * <p>
 * We're using this as an insertion point to patch up the Android PRNG disaster,
 * to initialize the job manager, and to check for GCM registration freshness.
 *
 * @author Moxie Marlinspike
 */
@HiltAndroidApp
public class ApplicationContext extends Application implements DefaultLifecycleObserver {

    public static final String PREFERENCES_NAME = "SecureSMS-Preferences";

    private static final String TAG = ApplicationContext.class.getSimpleName();

    private ExpiringMessageManager expiringMessageManager;
    private TypingStatusRepository typingStatusRepository;
    private TypingStatusSender typingStatusSender;
    private JobManager jobManager;
    private ReadReceiptManager readReceiptManager;
    private ProfileManager profileManager;
    public MessageNotifier messageNotifier = null;
    public Poller poller = null;
    public Broadcaster broadcaster = null;
    private Job firebaseInstanceIdJob;
    private WindowDebouncer conversationListDebouncer;
    private HandlerThread conversationListHandlerThread;
    private Handler conversationListHandler;
    private PersistentLogger persistentLogger;

    @Inject LokiAPIDatabase lokiAPIDatabase;
    @Inject Storage storage;
    @Inject MessageDataProvider messageDataProvider;
    @Inject JobDatabase jobDatabase;
    @Inject TextSecurePreferences textSecurePreferences;
    CallMessageProcessor callMessageProcessor;
    MessagingModuleConfiguration messagingModuleConfiguration;

    private volatile boolean isAppVisible;

    @Override
    public Object getSystemService(String name) {
        if (MessagingModuleConfiguration.MESSAGING_MODULE_SERVICE.equals(name)) {
            return messagingModuleConfiguration;
        }
        return super.getSystemService(name);
    }

    public static ApplicationContext getInstance(Context context) {
        return (ApplicationContext) context.getApplicationContext();
    }

    public DatabaseComponent getDatabaseComponent() {
        return EntryPoints.get(getApplicationContext(), DatabaseComponent.class);
    }

    public Handler getConversationListNotificationHandler() {
        if (this.conversationListHandlerThread == null) {
            conversationListHandlerThread = new HandlerThread("ConversationListHandler");
            conversationListHandlerThread.start();
        }
        if (this.conversationListHandler == null) {
            conversationListHandler = new Handler(conversationListHandlerThread.getLooper());
        }
        return conversationListHandler;
    }

    public WindowDebouncer getConversationListDebouncer() {
        if (conversationListDebouncer == null) {
            conversationListDebouncer = new WindowDebouncer(1000, new Timer());
        }
        return conversationListDebouncer;
    }

    public PersistentLogger getPersistentLogger() {
        return this.persistentLogger;
    }

    @Override
    public void onCreate() {
        DatabaseModule.init(this);
        MessagingModuleConfiguration.configure(this);
        super.onCreate();
        messagingModuleConfiguration = new MessagingModuleConfiguration(this,
                storage,
                messageDataProvider,
                ()-> KeyPairUtilities.INSTANCE.getUserED25519KeyPair(this));
        callMessageProcessor = new CallMessageProcessor(this, textSecurePreferences, ProcessLifecycleOwner.get().getLifecycle(), storage);
        Log.i(TAG, "onCreate()");
        startKovenant();
        initializeSecurityProvider();
        initializeLogging();
        initializeCrashHandling();
        NotificationChannels.create(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        AppContext.INSTANCE.configureKovenant();
        messageNotifier = new OptimizedMessageNotifier(new DefaultMessageNotifier());
        broadcaster = new Broadcaster(this);
        LokiAPIDatabase apiDB = getDatabaseComponent().lokiAPIDatabase();
        SnodeModule.Companion.configure(apiDB, broadcaster);
        String userPublicKey = TextSecurePreferences.getLocalNumber(this);
        if (userPublicKey != null) {
            registerForFCMIfNeeded(false);
        }
        UiModeUtilities.setupUiModeToUserSelected(this);
        initializeExpiringMessageManager();
        initializeTypingStatusRepository();
        initializeTypingStatusSender();
        initializeReadReceiptManager();
        initializeProfileManager();
        initializePeriodicTasks();
        SSKEnvironment.Companion.configure(getTypingStatusRepository(), getReadReceiptManager(), getProfileManager(), messageNotifier, getExpiringMessageManager());
        initializeJobManager();
        initializeWebRtc();
        initializeBlobProvider();
        resubmitProfilePictureIfNeeded();
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        isAppVisible = true;
        Log.i(TAG, "App is now visible.");
        KeyCachingService.onAppForegrounded(this);

        ThreadUtils.queue(()->{
            if (poller != null) {
                poller.setCaughtUp(false);
            }

            startPollingIfNeeded();

            OpenGroupManager.INSTANCE.startPolling();
        });
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        isAppVisible = false;
        Log.i(TAG, "App is no longer visible.");
        KeyCachingService.onAppBackgrounded(this);
        messageNotifier.setVisibleThread(-1);
        if (poller != null) {
            poller.stopIfNeeded();
        }
        ClosedGroupPollerV2.getShared().stop();
    }

    @Override
    public void onTerminate() {
        stopKovenant(); // Loki
        OpenGroupManager.INSTANCE.stopPolling();
        super.onTerminate();
    }

    public void initializeLocaleParser() {
        LocaleParser.Companion.configure(new LocaleParseHelper());
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public ExpiringMessageManager getExpiringMessageManager() {
        return expiringMessageManager;
    }

    public TypingStatusRepository getTypingStatusRepository() {
        return typingStatusRepository;
    }

    public TypingStatusSender getTypingStatusSender() {
        return typingStatusSender;
    }

    public ReadReceiptManager getReadReceiptManager() {
        return readReceiptManager;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public boolean isAppVisible() {
        return isAppVisible;
    }

    // Loki

    private void initializeSecurityProvider() {
        try {
            Class.forName("org.signal.aesgcmprovider.AesGcmCipher");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Failed to find AesGcmCipher class");
            throw new ProviderInitializationException();
        }

        int aesPosition = Security.insertProviderAt(new AesGcmProvider(), 1);
        Log.i(TAG, "Installed AesGcmProvider: " + aesPosition);

        if (aesPosition < 0) {
            Log.e(TAG, "Failed to install AesGcmProvider()");
            throw new ProviderInitializationException();
        }

        int conscryptPosition = Security.insertProviderAt(Conscrypt.newProvider(), 2);
        Log.i(TAG, "Installed Conscrypt provider: " + conscryptPosition);

        if (conscryptPosition < 0) {
            Log.w(TAG, "Did not install Conscrypt provider. May already be present.");
        }
    }

    private void initializeLogging() {
        if (persistentLogger == null) {
            persistentLogger = new PersistentLogger(this);
        }
        Log.initialize(new AndroidLogger(), persistentLogger);
    }

    private void initializeCrashHandling() {
        final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionLogger(originalHandler));
    }

    private void initializeJobManager() {
        this.jobManager = new JobManager(this, new JobManager.Configuration.Builder()
            .setDataSerializer(new JsonDataSerializer())
            .setJobFactories(JobManagerFactories.getJobFactories(this))
            .setConstraintFactories(JobManagerFactories.getConstraintFactories(this))
            .setConstraintObservers(JobManagerFactories.getConstraintObservers(this))
            .setJobStorage(new FastJobStorage(jobDatabase))
            .build());
    }

    private void initializeExpiringMessageManager() {
        this.expiringMessageManager = new ExpiringMessageManager(this);
    }

    private void initializeTypingStatusRepository() {
        this.typingStatusRepository = new TypingStatusRepository();
    }

    private void initializeReadReceiptManager() {
        this.readReceiptManager = new ReadReceiptManager();
    }

    private void initializeProfileManager() {
        this.profileManager = new ProfileManager();
    }

    private void initializeTypingStatusSender() {
        this.typingStatusSender = new TypingStatusSender(this);
    }

    private void initializePeriodicTasks() {
        BackgroundPollWorker.schedulePeriodic(this);

        if (BuildConfig.PLAY_STORE_DISABLED) {
            UpdateApkRefreshListener.schedule(this);
        }
    }

    private void initializeWebRtc() {
        try {
            Set<String> HARDWARE_AEC_BLACKLIST = new HashSet<String>() {{
                add("Pixel");
                add("Pixel XL");
                add("Moto G5");
                add("Moto G (5S) Plus");
                add("Moto G4");
                add("TA-1053");
                add("Mi A1");
                add("E5823"); // Sony z5 compact
                add("Redmi Note 5");
                add("FP2"); // Fairphone FP2
                add("MI 5");
            }};

            Set<String> OPEN_SL_ES_WHITELIST = new HashSet<String>() {{
                add("Pixel");
                add("Pixel XL");
            }};

            if (HARDWARE_AEC_BLACKLIST.contains(Build.MODEL)) {
                WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
            }

            if (!OPEN_SL_ES_WHITELIST.contains(Build.MODEL)) {
                WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
            }

            PeerConnectionFactory.initialize(InitializationOptions.builder(this).createInitializationOptions());
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, e);
        }
    }

    private void initializeBlobProvider() {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            BlobProvider.getInstance().onSessionStart(this);
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        initializeLocaleParser();
        super.attachBaseContext(DynamicLanguageContextWrapper.updateContext(base, TextSecurePreferences.getLanguage(base)));
    }

    private static class ProviderInitializationException extends RuntimeException { }

    public void registerForFCMIfNeeded(final Boolean force) {
        if (firebaseInstanceIdJob != null && firebaseInstanceIdJob.isActive() && !force) return;
        if (force && firebaseInstanceIdJob != null) {
            firebaseInstanceIdJob.cancel(null);
        }
        firebaseInstanceIdJob = FcmUtils.getFcmInstanceId(task->{
            if (!task.isSuccessful()) {
                Log.w("Loki", "FirebaseInstanceId.getInstance().getInstanceId() failed." + task.getException());
                return Unit.INSTANCE;
            }
            String token = task.getResult().getToken();
            String userPublicKey = TextSecurePreferences.getLocalNumber(this);
            if (userPublicKey == null) return Unit.INSTANCE;
            if (TextSecurePreferences.isUsingFCM(this)) {
                LokiPushNotificationManager.register(token, userPublicKey, this, force);
            } else {
                LokiPushNotificationManager.unregister(token, this);
            }
            return Unit.INSTANCE;
        });
    }

    private void setUpPollingIfNeeded() {
        String userPublicKey = TextSecurePreferences.getLocalNumber(this);
        if (userPublicKey == null) return;
        if (poller != null) {
            poller.setUserPublicKey(userPublicKey);
            return;
        }
        poller = new Poller();
    }

    public void startPollingIfNeeded() {
        setUpPollingIfNeeded();
        if (poller != null) {
            poller.startIfNeeded();
        }
        ClosedGroupPollerV2.getShared().start();
    }

    private void resubmitProfilePictureIfNeeded() {
        // Files expire on the file server after a while, so we simply re-upload the user's profile picture
        // at a certain interval to ensure it's always available.
        String userPublicKey = TextSecurePreferences.getLocalNumber(this);
        if (userPublicKey == null) return;
        long now = new Date().getTime();
        long lastProfilePictureUpload = TextSecurePreferences.getLastProfilePictureUpload(this);
        if (now - lastProfilePictureUpload <= 14 * 24 * 60 * 60 * 1000) return;
        ThreadUtils.queue(() -> {
            // Don't generate a new profile key here; we do that when the user changes their profile picture
            String encodedProfileKey = TextSecurePreferences.getProfileKey(ApplicationContext.this);
            try {
                // Read the file into a byte array
                InputStream inputStream = AvatarHelper.getInputStreamFor(ApplicationContext.this, Address.fromSerialized(userPublicKey));
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int count;
                byte[] buffer = new byte[1024];
                while ((count = inputStream.read(buffer, 0, buffer.length)) != -1) {
                    baos.write(buffer, 0, count);
                }
                baos.flush();
                byte[] profilePicture = baos.toByteArray();
                // Re-upload it
                ProfilePictureUtilities.INSTANCE.upload(profilePicture, encodedProfileKey, ApplicationContext.this).success(unit -> {
                    // Update the last profile picture upload date
                    TextSecurePreferences.setLastProfilePictureUpload(ApplicationContext.this, new Date().getTime());
                    return Unit.INSTANCE;
                });
            } catch (Exception exception) {
                // Do nothing
            }
        });
    }

    public void clearAllData(boolean isMigratingToV2KeyPair) {
        String token = TextSecurePreferences.getFCMToken(this);
        if (token != null && !token.isEmpty()) {
            LokiPushNotificationManager.unregister(token, this);
        }
        if (firebaseInstanceIdJob != null && firebaseInstanceIdJob.isActive()) {
            firebaseInstanceIdJob.cancel(null);
        }
        String displayName = TextSecurePreferences.getProfileName(this);
        boolean isUsingFCM = TextSecurePreferences.isUsingFCM(this);
        TextSecurePreferences.clearAll(this);
        if (isMigratingToV2KeyPair) {
            TextSecurePreferences.setIsUsingFCM(this, isUsingFCM);
            TextSecurePreferences.setProfileName(this, displayName);
        }
        getSharedPreferences(PREFERENCES_NAME, 0).edit().clear().commit();
        if (!deleteDatabase("signal.db")) {
            Log.d("Loki", "Failed to delete database.");
        }
        Util.runOnMain(() -> new Handler().postDelayed(ApplicationContext.this::restartApplication, 200));
    }

    public void restartApplication() {
        Intent intent = new Intent(this, HomeActivity.class);
        startActivity(Intent.makeRestartActivityTask(intent.getComponent()));
        Runtime.getRuntime().exit(0);
    }

    // endregion
}
