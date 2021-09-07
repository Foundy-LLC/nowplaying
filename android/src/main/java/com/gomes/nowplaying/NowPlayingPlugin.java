package com.gomes.nowplaying;

import androidx.annotation.NonNull;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.text.TextUtils;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * NowPlayingPlugin
 */
public class NowPlayingPlugin implements FlutterPlugin, MethodCallHandler {
    public static final String ACTION = "com.gomes.nowplaying";

    private static final String ENABLED_NOTIFICATION_LISTENERS =
            "enabled_notification_listeners";
    private static final String ACTION_NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private static final String COMMAND_TRACK = "track";
    private static final String COMMAND_START = "start";
    private static final String COMMAND_SHOW_FLOATING_WINDOW = "showFloatingWindow";
    private static final String COMMAND_ENABLED = "isEnabled";
    private static final String COMMAND_REQUEST_PERMISSIONS = "requestPermissions";
    private static final String COMMAND_PLAY_OR_PAUSE = "playOrPause";
    private static final String COMMAND_SKIP_TO_PREVIOUS = "skipToPrevious";
    private static final String COMMAND_SKIP_TO_NEXT = "skipToNext";

    private static final int STATE_PLAYING = 0;
    private static final int STATE_PAUSED = 1;
    public static final int STATE_STOPPED = 2;
    public static final int STATE_UNKNOWN = -1;

    /// The MethodChannel that will the communication between Flutter and native
    /// Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine
    /// and unregister it
    /// when the Flutter Engine is detached from the Activity
    public static MethodChannel channel;
    public static Map<String, Object> trackData = new HashMap<>();
    private ChangeBroadcastReceiver changeBroadcastReceiver;
    private Context context;

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        boolean isEnabled;

        switch (call.method) {
            case COMMAND_START:
                Object arguments = call.arguments;
                try {
                    ArrayList<Long> list = (ArrayList<Long>) arguments;
                    long callbackHandle = list.get(0);
                    long searchLyricCallbackHandle = list.get(1);

                    SharedPreferences prefs = context.getSharedPreferences(
                            FloatingWindowService.SHARED_PREFS_KEY,
                            Context.MODE_PRIVATE);
                    prefs.edit()
                            .putLong(FloatingWindowService.SEARCH_CALLBACK_KEY, searchLyricCallbackHandle)
                            .apply();

                    FloatingWindowService.setCallbackDispatcher(context, callbackHandle);
                    FloatingWindowService.startBackgroundIsolate(context, callbackHandle);
                } catch (Exception e) {
                    e.printStackTrace();
                    result.success(false);
                    return;
                }
                result.success(true);
                break;
            case COMMAND_SHOW_FLOATING_WINDOW:
                FloatingWindowService.startFloatingService(context, true);
                result.success(true);
                break;
            case COMMAND_TRACK:
                if (NowPlayingListenerService.lastToken != null && NowPlayingListenerService.lastIcon != null) {
                    final Map<String, Object> data = extractFieldsFor(
                            context,
                            NowPlayingListenerService.lastToken,
                            NowPlayingListenerService.lastIcon);
                    if (data != null) sendTrack(data);
                }
                result.success(trackData);
                break;
            case COMMAND_ENABLED:
                isEnabled = isNotificationListenerServiceEnabled();
                result.success(isEnabled);
                break;
            case COMMAND_REQUEST_PERMISSIONS:
                isEnabled = isNotificationListenerServiceEnabled();
                if (!isEnabled) {
                    Intent intent = new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
                result.success(true);
                break;
            case COMMAND_PLAY_OR_PAUSE:
                new NowPlayingController(context).playOrPause(result);
                break;
            case COMMAND_SKIP_TO_PREVIOUS:
                new NowPlayingController(context).skipToPrevious(result);
                break;
            case COMMAND_SKIP_TO_NEXT:
                new NowPlayingController(context).skipToNext(result);
                break;
            default:
                result.notImplemented();
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        if (channel != null) {
            return;
        }
        context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "gomes.com.es/nowplaying");

        changeBroadcastReceiver = new ChangeBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NowPlayingPlugin.ACTION);
        context.registerReceiver(changeBroadcastReceiver, intentFilter);
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        if (context != null) {
            context.unregisterReceiver(changeBroadcastReceiver);
        }
        channel = null;
        changeBroadcastReceiver = null;
    }

    public class ChangeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context != null) {
                final String action = intent.getStringExtra(NowPlayingListenerService.FIELD_ACTION);
                final Icon icon = intent.getParcelableExtra(NowPlayingListenerService.FIELD_ICON);
                final MediaSession.Token token = intent.getParcelableExtra(NowPlayingListenerService.FIELD_TOKEN);

                if (NowPlayingListenerService.ACTION_POSTED.equals(action)) {
                    final Map<String, Object> data = extractFieldsFor(context, token, icon);
                    if (data != null) {
                        sendTrack(data);
                    }
                }
            }
        }
    }

    private boolean isNotificationListenerServiceEnabled() {
        final String pkgName = context.getPackageName();
        final String flat = Settings.Secure.getString(context.getContentResolver(), ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) return true;
            }
        }
        return false;
    }

    private void sendTrack(Map<String, Object> data) {
        if (data != null) trackData = data;

        ArrayList<Object> arguments = new ArrayList<>();
        arguments.add(data);

        try {
            channel.invokeMethod(COMMAND_TRACK, arguments);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static Map<String, Object> extractFieldsFor(Context context, MediaSession.Token token, Icon icon) {
        final MediaController controller = new MediaController(context, token);

        final MediaMetadata mediaMetadata = controller.getMetadata();
        if (mediaMetadata == null) return null;

        final String id = deriveId(mediaMetadata);
        final String lastId = (String) trackData.get("id");

        final PlaybackState playbackState = controller.getPlaybackState();
        final int state = getPlaybackState(playbackState);

        // back out now if we're not interested in this state
        if (state == STATE_UNKNOWN) return null;
        if (state == STATE_STOPPED && !id.equals(lastId)) return null;

        final Map<String, Object> data = new HashMap<>();

        data.put("id", id);
        data.put("source", controller.getPackageName());
        data.put("state", state);

        data.put("album", mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        data.put("title", mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        data.put("artist", mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        data.put("genre", mediaMetadata.getString(MediaMetadata.METADATA_KEY_GENRE));
        data.put("duration", mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        data.put("position", playbackState.getPosition());

        // do the onerous imagey stuff only if we're on a new paused or playing media item
        data.put("sourceIcon", convertIcon(context, icon));

        byte[] image = extractBitmap(mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART));
        if (image == null)
            image = extractBitmap(mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
        if (image != null) {
            data.put("image", image);
        } else {
            String imageUri = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (imageUri == null)
                imageUri = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            data.put("imageUri", imageUri);
        }

        return data;
    }

    public static String deriveId(MediaMetadata mediaMetadata) {
        final String album = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        final String title = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String artist = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return title + ":" + artist + ":" + album;
    }

    public static byte[] extractBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);

        return stream.toByteArray();
    }

    public static byte[] convertIcon(Context context, Icon icon) {
        final Drawable drawable = icon.loadDrawable(context);
        if (drawable instanceof BitmapDrawable) {
            final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            return extractBitmap(bitmap);
        } else if (drawable instanceof VectorDrawable) {
            final VectorDrawable vector = (VectorDrawable) drawable;
            final Bitmap bitmap = Bitmap.createBitmap(vector.getIntrinsicWidth(), vector.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            vector.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            vector.draw(canvas);
            return extractBitmap(bitmap);
        }
        return null;
    }

    public static int getPlaybackState(PlaybackState state) {
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                return NowPlayingPlugin.STATE_PLAYING;
            case PlaybackState.STATE_PAUSED:
                return NowPlayingPlugin.STATE_PAUSED;
            case PlaybackState.STATE_STOPPED:
                return NowPlayingPlugin.STATE_STOPPED;
            default:
                return NowPlayingPlugin.STATE_UNKNOWN;
        }
    }
}
