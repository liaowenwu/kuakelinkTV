package com.gathin.tvhome.cloud.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.gathin.tvhome.R;
import com.gathin.tvhome.cloud.quark.QuarkTvClient;

/**
 * 夸克网盘 TV 版官方扫码登录对话框
 * 遵循 Apple TV 极简深色毛玻璃美学设计，支持：
 * 1. 自动请求并渲染高清 TV 授权二维码
 * 2. 2秒无感后台轮询扫码确认状态
 * 3. 授权确认后自动换取永久 RefreshToken 并关闭对话框回执
 */
public class QuarkAuthDialog extends Dialog {

    public interface OnAuthSuccessListener {
        void onAuthSuccess();
    }

    private final OnAuthSuccessListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String customTitle;
    private final String customSubtitle;

    private ImageView qrImage;
    private ProgressBar qrLoading;
    private TextView qrStatus;

    private String currentQueryToken;
    private boolean isPolling = false;
    private int pollCount = 0;

    public QuarkAuthDialog(@NonNull Context context, OnAuthSuccessListener listener) {
        this(context, null, null, listener);
    }

    public QuarkAuthDialog(@NonNull Context context, String title, String subtitle, OnAuthSuccessListener listener) {
        super(context);
        this.customTitle = title;
        this.customSubtitle = subtitle;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_quark_auth);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.72f);
        }

        TextView titleView = findViewById(R.id.dialogTitle);
        TextView subtitleView = findViewById(R.id.dialogSubtitle);
        if (customTitle != null && titleView != null) titleView.setText(customTitle);
        if (customSubtitle != null && subtitleView != null) subtitleView.setText(customSubtitle);

        qrImage = findViewById(R.id.qrImage);
        qrLoading = findViewById(R.id.qrLoading);
        qrStatus = findViewById(R.id.qrStatus);

        findViewById(R.id.qrImage).setOnClickListener(v -> {
            if (!isPolling) {
                fetchQrCode();
            }
        });

        fetchQrCode();
    }

    private void fetchQrCode() {
        if (qrLoading != null) qrLoading.setVisibility(View.VISIBLE);
        if (qrImage != null) qrImage.setVisibility(View.INVISIBLE);
        if (qrStatus != null) qrStatus.setText("正在连接夸克服务生成二维码...");

        QuarkTvClient.getInstance().getLoginQrCode(new QuarkTvClient.Callback<QuarkTvClient.QrResult>() {
            @Override
            public void onSuccess(QuarkTvClient.QrResult result) {
                if (!isShowing()) return;
                currentQueryToken = result.queryToken;
                if (qrLoading != null) qrLoading.setVisibility(View.GONE);
                if (qrImage != null) {
                    qrImage.setImageBitmap(result.qrBitmap);
                    qrImage.setVisibility(View.VISIBLE);
                }
                if (qrStatus != null) qrStatus.setText("请使用手机【夸克 App】扫码确认");
                startPolling();
            }

            @Override
            public void onError(String errorMsg) {
                if (!isShowing()) return;
                if (qrLoading != null) qrLoading.setVisibility(View.GONE);
                if (qrStatus != null) qrStatus.setText("获取二维码失败（点击重试）：" + errorMsg);
            }
        });
    }

    private void startPolling() {
        stopPolling();
        isPolling = true;
        pollCount = 0;
        scheduleNextPoll();
    }

    private void scheduleNextPoll() {
        if (!isPolling || !isShowing() || currentQueryToken == null) return;

        handler.postDelayed(() -> {
            if (!isPolling || !isShowing()) return;
            pollCount++;

            // 动态提示，展示轮询呼吸感
            if (qrStatus != null) {
                String dots = "...".substring(0, (pollCount % 3) + 1);
                qrStatus.setText("等待手机扫码确认中" + dots);
            }

            QuarkTvClient.getInstance().pollAuthCode(currentQueryToken, new QuarkTvClient.Callback<String>() {
                @Override
                public void onSuccess(String code) {
                    if (!isShowing()) return;

                    if (code == null) {
                        // 仍在等待扫码，继续下一次轮询（最长等待 5 分钟）
                        if (pollCount < 150) {
                            scheduleNextPoll();
                        } else {
                            if (qrStatus != null) qrStatus.setText("二维码已过期，点击重新获取");
                            isPolling = false;
                        }
                    } else {
                        // 授权成功！换取持久 Tokens
                        stopPolling();
                        if (qrStatus != null) qrStatus.setText("扫码成功，正在同步网盘凭证...");
                        exchangeCode(code);
                    }
                }

                @Override
                public void onError(String errorMsg) {
                    if (!isShowing()) return;
                    // 网络抖动，继续重试
                    scheduleNextPoll();
                }
            });
        }, 2000);
    }

    private void exchangeCode(String code) {
        QuarkTvClient.getInstance().exchangeCodeForTokens(code, new QuarkTvClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean success) {
                Toast.makeText(getContext(), "🎉 夸克网盘授权成功！", Toast.LENGTH_SHORT).show();
                dismiss();
                if (listener != null) {
                    listener.onAuthSuccess();
                }
            }

            @Override
            public void onError(String errorMsg) {
                if (qrStatus != null) qrStatus.setText("换取授权凭证失败：" + errorMsg);
            }
        });
    }

    private void stopPolling() {
        isPolling = false;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void dismiss() {
        stopPolling();
        super.dismiss();
    }
}
