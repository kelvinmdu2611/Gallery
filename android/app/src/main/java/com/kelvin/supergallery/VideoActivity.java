package com.kelvin.supergallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Full-screen video playback with Android's native player (Media3 / ExoPlayer).
 * It reads the video file directly and uses hardware decoding, so playback
 * starts at once and seeking is instant, like the system gallery.
 */
public class VideoActivity extends Activity {
    public static final String EXTRA_URI = "uri";

    private PlayerView view;
    private ImageButton close;
    private ExoPlayer player;
    private Uri uri;
    private long position = 0;
    private boolean playWhenReady = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String u = getIntent().getStringExtra(EXTRA_URI);
        if (u == null) { finish(); return; }
        uri = Uri.parse(u);
        if (state != null) {
            position = state.getLong("pos", 0);
            playWhenReady = state.getBoolean("play", true);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        view.setControllerShowTimeoutMs(2500);
        view.setControllerHideOnTouch(true);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_video_back);
        close.setBackgroundResource(R.drawable.bg_round_button);
        close.setContentDescription("Close");
        int size = dp(44);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(16);
        lp.topMargin = dp(16);
        root.addView(close, lp);
        close.setOnClickListener(v -> finish());
        ViewCompat.setOnApplyWindowInsetsListener(close, (v, insets) -> {
            int top = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            p.topMargin = dp(16) + top;
            v.setLayoutParams(p);
            return insets;
        });
        // the close button shows and hides together with the playback controls
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                close.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE));

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (uri == null) return;
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(VideoActivity.this, "Can’t play this video", Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) view.showController();
            }
        });
        view.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.seekTo(position);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            position = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("pos", position);
        out.putBoolean("play", playWhenReady);
    }

    /** Start playback of a content:// video. */
    static void play(Activity from, Uri uri) {
        Intent i = new Intent(from, VideoActivity.class);
        i.putExtra(EXTRA_URI, uri.toString());
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        from.startActivity(i);
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
