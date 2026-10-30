package com.herrylok.tvcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.herrylok.tvcast.core.RendererServer;
import java.net.Inet4Address;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private String deviceName;
    private static final int CREAM = Color.rgb(255, 246, 236), PEACH = Color.rgb(253, 230, 212), CORAL = Color.rgb(232, 113, 74),
            BROWN = Color.rgb(62, 44, 35), MUTED = Color.rgb(140, 118, 106), CARD = Color.WHITE, PILL = Color.rgb(255, 240, 228),
            OK = Color.rgb(92, 170, 108), WAIT = Color.rgb(240, 172, 68), BAD = Color.rgb(214, 84, 70);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile RendererServer receiver;
    private boolean visible;
    private String address = "", uuid, latest = "正在准备接收服务";
    private TextView status, networkText;
    private View statusDot;
    private Button restartButton;
    private LinearLayout home;
    private PlayerView playerView;
    private ExoPlayer player;
    private PlayerAdapter adapter;
    private WifiManager.MulticastLock multicast;
    private ConnectivityManager connectivity;
    private final Runnable networkChanged = () -> restart(false);
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        private void changed() { main.post(() -> { main.removeCallbacks(networkChanged); if (visible) main.postDelayed(networkChanged, 500); }); }
        @Override public void onAvailable(Network n) { changed(); }
        @Override public void onLost(Network n) { changed(); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { changed(); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        deviceName = getString(R.string.app_name);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences prefs = getSharedPreferences("receiver", MODE_PRIVATE);
        uuid = prefs.getString("uuid", null);
        if (uuid == null) { uuid = "uuid:" + UUID.randomUUID(); prefs.edit().putString("uuid", uuid).apply(); }
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        buildScreen();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private GradientDrawable card(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable oval(int color) { GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color); return d; }
    private Button button(String title, Runnable click) {
        Button b = new Button(this); b.setText(title); b.setTextSize(16); b.setAllCaps(false); b.setStateListAnimator(null);
        b.setTextColor(BROWN); b.setBackground(card(CARD, 28)); b.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(184), dp(56)); lp.setMarginEnd(dp(18)); b.setLayoutParams(lp);
        b.setOnFocusChangeListener((view, focus) -> {
            b.setBackground(card(focus ? CORAL : CARD, 28)); b.setTextColor(focus ? Color.WHITE : BROWN);
            b.setElevation(dp(focus ? 8 : 2)); b.animate().scaleX(focus ? 1.06f : 1f).scaleY(focus ? 1.06f : 1f).setDuration(150).start();
        });
        b.setOnClickListener(v -> click.run()); return b;
    }
    private ImageView logo(int size) {
        ImageView view = new ImageView(this); view.setImageResource(R.drawable.ic_logo);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size))); return view;
    }
    private View blob(int size, int gravity, int dx, int dy) {
        View view = new View(this); view.setBackground(oval(PEACH));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(size), dp(size), gravity);
        lp.setMargins(dx < 0 ? dp(dx) : 0, dy < 0 ? dp(dy) : 0, dx > 0 ? -dp(dx) : 0, dy > 0 ? -dp(dy) : 0); view.setLayoutParams(lp); return view;
    }
    private View step(int number, String text) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = label(String.valueOf(number), 14, Color.WHITE); badge.setGravity(Gravity.CENTER); badge.setPadding(0, 0, 0, 0);
        badge.setTypeface(null, Typeface.BOLD); badge.setBackground(oval(CORAL)); row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView body = label(text, 16, BROWN); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginStart(dp(14)); row.addView(body, lp);
        row.setPadding(0, dp(5), 0, dp(5)); return row;
    }
    private void buildScreen() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(CREAM);
        root.addView(blob(460, Gravity.TOP | Gravity.END, 140, -170));
        root.addView(blob(260, Gravity.BOTTOM | Gravity.START, -110, 120));
        playerView = new PlayerView(this); playerView.setVisibility(View.GONE); playerView.setBackgroundColor(Color.BLACK);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setShowNextButton(false); playerView.setShowPreviousButton(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        home = new LinearLayout(this); home.setOrientation(LinearLayout.VERTICAL); home.setPadding(dp(56), dp(32), dp(56), dp(28));

        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(logo(46));
        TextView name = label(deviceName, 22, BROWN); name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2); nameParams.setMarginStart(dp(8)); brand.addView(name, nameParams);
        TextView version = label("v" + BuildConfig.VERSION_NAME, 13, MUTED);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-2, -2); versionParams.setMarginStart(dp(12)); brand.addView(version, versionParams);
        home.addView(brand);

        LinearLayout content = new LinearLayout(this); content.setGravity(Gravity.CENTER_VERTICAL);
        home.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout intro = new LinearLayout(this); intro.setOrientation(LinearLayout.VERTICAL);
        content.addView(intro, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = label("一家人，一起看大屏。", 38, BROWN); title.setTypeface(null, Typeface.BOLD); intro.addView(title);
        intro.addView(label("手机里好看的，随手投到客厅电视上。", 17, MUTED));
        LinearLayout steps = new LinearLayout(this); steps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stepsParams = new LinearLayout.LayoutParams(-2, -2); stepsParams.topMargin = dp(20); stepsParams.bottomMargin = dp(26);
        steps.addView(step(1, "手机和电视连接同一个家庭 Wi-Fi"));
        steps.addView(step(2, "在视频 App 播放页点击「TV」图标"));
        steps.addView(step(3, "选择「" + deviceName + "」，开始一起看"));
        intro.addView(steps, stepsParams);
        LinearLayout buttons = new LinearLayout(this);
        restartButton = button("重启接收服务", () -> restart(true)); buttons.addView(restartButton);
        buttons.addView(button("连接帮助", this::help)); intro.addView(buttons);

        LinearLayout device = new LinearLayout(this); device.setOrientation(LinearLayout.VERTICAL); device.setGravity(Gravity.CENTER_HORIZONTAL);
        device.setPadding(dp(32), dp(28), dp(32), dp(28)); device.setBackground(card(CARD, 28)); device.setElevation(dp(6));
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(dp(340), -2); deviceParams.setMarginStart(dp(40)); content.addView(device, deviceParams);
        device.addView(logo(96));
        TextView hint = label("投屏到这台电视", 13, MUTED); hint.setGravity(Gravity.CENTER); device.addView(hint);
        TextView tv = label(deviceName, 30, BROWN); tv.setTypeface(null, Typeface.BOLD); tv.setGravity(Gravity.CENTER); device.addView(tv);
        LinearLayout pill = new LinearLayout(this); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setBackground(card(PILL, 20));
        pill.setPadding(dp(16), dp(6), dp(18), dp(6));
        statusDot = new View(this); statusDot.setBackground(oval(WAIT)); pill.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        status = label(latest, 15, BROWN); status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2); statusParams.setMarginStart(dp(10)); pill.addView(status, statusParams);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2); pillParams.topMargin = dp(12); device.addView(pill, pillParams);
        networkText = label("正在读取网络…", 13, MUTED); networkText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams networkParams = new LinearLayout.LayoutParams(-2, -2); networkParams.topMargin = dp(10); device.addView(networkText, networkParams);

        home.addView(label("请保持此页面打开  ·  退出 App 或电视待机后将停止接收投屏", 13, MUTED));
        for (ViewGroup group : new ViewGroup[] { home, content, intro, buttons }) { group.setClipChildren(false); group.setClipToPadding(false); }
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root); restartButton.requestFocus();
    }
    @Override public void onStart() {
        super.onStart(); visible = true;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("EasyCast/" + BuildConfig.VERSION_NAME)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        adapter = new PlayerAdapter(player, () -> { home.setVisibility(View.GONE); playerView.setVisibility(View.VISIBLE); playerView.requestFocus(); });
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                showHome(); update("播放失败：" + error.getErrorCodeName() + "。可换一个视频测试，或检查内容投屏权限。");
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) showHome();
            }
        });
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicast = wifi.createMulticastLock("easycast"); multicast.setReferenceCounted(false); multicast.acquire();
        }
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback);
        restart(true);
    }
    private void update(String text) {
        latest = text; status.setText(text);
        boolean bad = text.contains("失败") || text.contains("中断"), ok = text.startsWith("接收服务已启动") || text.startsWith("已收到");
        statusDot.setBackground(oval(bad ? BAD : ok ? OK : WAIT));
    }
    private String lanAddress() {
        for (Network net : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(net);
            if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties props = connectivity.getLinkProperties(net); if (props == null) continue;
            for (LinkAddress link : props.getLinkAddresses()) if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()) return link.getAddress().getHostAddress();
        }
        return "";
    }
    private void restart(boolean force) {
        if (!visible) return;
        String next = lanAddress();
        if (!force && next.equals(address) && receiver != null) return;
        address = next; int version = generation.incrementAndGet(); PlayerAdapter currentPlayer = adapter;
        networkText.setText(next.isEmpty() ? "尚未连接可用的 IPv4 家庭网络" : "电视 IP  " + next);
        update(next.isEmpty() ? "等待网络连接" : "正在启动接收服务…");
        network.execute(() -> {
            RendererServer old = receiver; receiver = null; if (old != null) old.close();
            if (next.isEmpty() || version != generation.get()) return;
            RendererServer candidate = new RendererServer(next, uuid, deviceName, currentPlayer,
                    text -> main.post(() -> { if (visible && version == generation.get()) update(text); }));
            try {
                candidate.start();
                if (version != generation.get()) candidate.close(); else receiver = candidate;
            } catch (Exception e) {
                candidate.close(); main.post(() -> { if (visible && version == generation.get()) update("接收服务启动失败，请关闭其他投屏 App 后重试。"); });
            }
        });
    }
    private void help() {
        new AlertDialog.Builder(this, R.style.HelpDialog).setTitle("连接到电视")
                .setMessage("1. 手机与电视连接同一家庭网络，避免访客 Wi-Fi。\n\n2. 如果使用 iPhone：设置 → 隐私与安全性 → 本地网络，允许视频 App 访问。\n\n3. 打开视频 App 播放页，点击 TV，选择「" + deviceName + "」。\n\n此版本接收兼容的视频 App 投屏，尚不支持手机整屏镜像。\n\n请保持 App 在前台；退出或电视待机后接收服务会停止。不同电视与视频 App 的兼容性需实机验证。\n\n当前状态：" + latest)
                .setPositiveButton("知道了", null).show();
    }
    private void showHome() { playerView.setVisibility(View.GONE); home.setVisibility(View.VISIBLE); restartButton.requestFocus(); }
    @Override public void onBackPressed() {
        if (playerView.getVisibility() == View.VISIBLE) { player.stop(); showHome(); }
        else super.onBackPressed();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerView.getVisibility() == View.VISIBLE && playerView.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onStop() {
        visible = false; generation.incrementAndGet(); main.removeCallbacks(networkChanged);
        connectivity.unregisterNetworkCallback(callback);
        if (adapter != null) adapter.release();
        network.execute(() -> { RendererServer old = receiver; receiver = null; if (old != null) old.close(); });
        if (multicast != null && multicast.isHeld()) multicast.release();
        playerView.setPlayer(null); if (player != null) { player.release(); player = null; }
        showHome(); super.onStop();
    }
    @Override public void onDestroy() { network.shutdown(); super.onDestroy(); }
}
