package com.gathin.tvhome.cloud.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.R;

import java.util.List;

/**
 * 电影系列大片展架弹窗 (Apple TV 极简展架风格)
 * 点击系列合集（如《漫威蜘蛛侠系列》、《阿凡达系列》）后展开，
 * 横向排列展示系列内各部电影，支持遥控器左右平滑浏览，OK 键直接起播。
 */
public class MovieCollectionDialog extends Dialog {

    public interface OnMovieSelectedListener {
        void onMovieSelected(MainActivity.CinemaSubItem movie);
    }

    private final String collectionTitle;
    private final String collectionMeta;
    private final List<MainActivity.CinemaSubItem> movies;
    private final OnMovieSelectedListener listener;

    public static boolean isIgnoredCollectionItem(String title) {
        if (title == null) return true;
        String s = title.trim().toLowerCase();
        return s.contains("海报") || s.contains("poster") || s.contains("fanart")
                || s.contains("extra") || s.contains("花絮") || s.contains("预告")
                || s.contains("trailer") || s.contains("sample") || s.contains("字幕")
                || s.contains("subs") || s.contains("screens") || s.contains("剧照");
    }

    public MovieCollectionDialog(@NonNull Context context,
                                 String collectionTitle,
                                 String collectionMeta,
                                 List<MainActivity.CinemaSubItem> movies,
                                 OnMovieSelectedListener listener) {
        super(context);
        this.collectionTitle = collectionTitle;
        this.collectionMeta = collectionMeta;
        java.util.List<MainActivity.CinemaSubItem> filtered = new java.util.ArrayList<>();
        if (movies != null) {
            for (MainActivity.CinemaSubItem m : movies) {
                if (m != null && !isIgnoredCollectionItem(m.title)) {
                    filtered.add(m);
                }
            }
        }
        this.movies = filtered;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_movie_collection);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.72f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }

        TextView tvTitle = findViewById(R.id.tvCollectionTitle);
        TextView tvMeta = findViewById(R.id.tvCollectionMeta);
        RecyclerView rvMovies = findViewById(R.id.rvCollectionMovies);

        tvTitle.setText(collectionTitle);
        if (collectionMeta != null && !collectionMeta.isEmpty()) {
            tvMeta.setText(collectionMeta);
        } else {
            tvMeta.setText(String.format("系列合集 · 共 %d 部大片全收录 · 4K 杜比视界", movies.size()));
        }

        rvMovies.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        rvMovies.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_collection_movie, parent, false);
                View card = v.findViewById(R.id.card);
                if (card != null) {
                    card.setClipToOutline(true);
                }
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                MainActivity.CinemaSubItem m = movies.get(position);
                View card = holder.itemView.findViewById(R.id.card);
                ImageView banner = holder.itemView.findViewById(R.id.banner);
                TextView title = holder.itemView.findViewById(R.id.movieTitle);
                TextView meta = holder.itemView.findViewById(R.id.movieMeta);

                banner.setImageDrawable(null);
                final String itemThumb = m.thumbnailUrl;
                if (itemThumb != null && !itemThumb.isEmpty()) {
                    com.gathin.tvhome.cloud.quark.ImageLoader.load(itemThumb, banner);
                } else {
                    banner.setImageResource(R.drawable.banner_quark);
                }

                title.setText(m.title);
                meta.setText(m.meta);

                card.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(160).start();
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(160).start();
                    }
                });

                final int pos = position;
                final int total = movies.size();
                card.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        dismiss();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                        v.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos >= total - 1) {
                        v.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
                        return true;
                    }
                    return false;
                });

                card.setOnClickListener(v -> {
                    dismiss();
                    if (listener != null) listener.onMovieSelected(m);
                });
            }

            @Override
            public int getItemCount() {
                return movies.size();
            }
        });

        // 默认将焦点落在第一部电影卡片上
        rvMovies.postDelayed(() -> {
            if (rvMovies.getChildCount() > 0) {
                View first = rvMovies.getChildAt(0).findViewById(R.id.card);
                if (first != null) first.requestFocus();
            }
        }, 50);
    }
}
