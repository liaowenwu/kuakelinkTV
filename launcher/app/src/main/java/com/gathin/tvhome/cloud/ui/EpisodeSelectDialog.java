package com.gathin.tvhome.cloud.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.R;
import com.gathin.tvhome.cloud.quark.QuarkTvClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 电视端剧集/动漫桌面选集面板
 * 遵循 Apple TV 极简深色毛玻璃美学设计，支持：
 * 1. 单季/多季无缝切换（分季标签页 Season Tabs）
 * 2. 跨季记忆上次观看集数与断点时间戳（如“继续播放 第 2 季 第 3 集 21:40”）
 * 3. 6列网格顺畅遥控器方向键选择
 * 4. 一键点击直接调起原生播放器
 */
public class EpisodeSelectDialog extends Dialog {

    public static class SeasonData {
        public final String seasonTitle;
        public final int seasonNumber;
        public final String folderFid;
        public final String path;
        public List<QuarkTvClient.CloudEpisode> episodes;

        public SeasonData(String seasonTitle, int seasonNumber, String folderFid, String path, List<QuarkTvClient.CloudEpisode> episodes) {
            this.seasonTitle = seasonTitle;
            this.seasonNumber = seasonNumber;
            this.folderFid = folderFid;
            this.path = path;
            this.episodes = episodes;
        }
    }

    public interface OnEpisodeSelectedListener {
        void onEpisodeSelected(int episodeIndex);
    }

    public interface OnSeasonEpisodeSelectedListener {
        void onSeasonEpisodeSelected(SeasonData season, int episodeIndex);
    }

    private final String seriesTitle;
    private final List<SeasonData> seasons = new ArrayList<>();
    private final OnSeasonEpisodeSelectedListener seasonListener;
    private int currentSeasonIndex = 0;

    private TextView tvTitle;
    private TextView tvMeta;
    private RecyclerView rvSeasonTabs;
    private TextView btnResume;
    private RecyclerView rvEpisodes;
    private SeasonTabAdapter seasonTabAdapter;
    private EpisodeGridAdapter episodeGridAdapter;
    private SharedPreferences prefs;

    // 单季构造函数（向下兼容）
    public EpisodeSelectDialog(@NonNull Context context, String seriesTitle,
                               List<QuarkTvClient.CloudEpisode> episodes,
                               OnEpisodeSelectedListener singleListener) {
        this(context, seriesTitle, wrapSingleSeason(seriesTitle, episodes), (season, episodeIndex) -> {
            if (singleListener != null) singleListener.onEpisodeSelected(episodeIndex);
        });
    }

    private static List<SeasonData> wrapSingleSeason(String title, List<QuarkTvClient.CloudEpisode> episodes) {
        List<SeasonData> list = new ArrayList<>();
        list.add(new SeasonData(title, 1, null, null, episodes));
        return list;
    }

    // 多季构造函数
    public EpisodeSelectDialog(@NonNull Context context, String seriesTitle,
                               List<SeasonData> seasonList,
                               OnSeasonEpisodeSelectedListener listener) {
        super(context);
        this.seriesTitle = seriesTitle;
        if (seasonList != null) this.seasons.addAll(seasonList);
        this.seasonListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_episode_select);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.75f);
        }

        prefs = getContext().getSharedPreferences("video_player_progress", Context.MODE_PRIVATE);

        tvTitle = findViewById(R.id.tvDialogSeriesTitle);
        tvMeta = findViewById(R.id.tvDialogSeriesMeta);
        rvSeasonTabs = findViewById(R.id.rvSeasonTabs);
        btnResume = findViewById(R.id.btnResumeEpisode);
        rvEpisodes = findViewById(R.id.rvDialogEpisodes);

        tvTitle.setText(seriesTitle);

        // 如果存在多季，展示分季栏
        if (seasons.size() > 1) {
            rvSeasonTabs.setVisibility(View.VISIBLE);
            rvSeasonTabs.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
            seasonTabAdapter = new SeasonTabAdapter();
            rvSeasonTabs.setAdapter(seasonTabAdapter);

            // 智能定位到上次观看的季度
            int lastWatchedSeason = prefs.getInt("last_season_" + seriesTitle, 1);
            for (int i = 0; i < seasons.size(); i++) {
                if (seasons.get(i).seasonNumber == lastWatchedSeason) {
                    currentSeasonIndex = i;
                    break;
                }
            }
        } else {
            rvSeasonTabs.setVisibility(View.GONE);
            currentSeasonIndex = 0;
        }

        rvEpisodes.setLayoutManager(new GridLayoutManager(getContext(), 6));
        episodeGridAdapter = new EpisodeGridAdapter();
        rvEpisodes.setAdapter(episodeGridAdapter);

        btnResume.setOnFocusChangeListener((v, hasFocus) -> {
            btnResume.setTextColor(hasFocus ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(hasFocus ? 1.02f : 1.0f).scaleY(hasFocus ? 1.02f : 1.0f).setDuration(140).start();
        });

        loadCurrentSeasonData();
    }

    private void loadCurrentSeasonData() {
        if (seasons.isEmpty()) return;
        SeasonData season = seasons.get(currentSeasonIndex);

        if (seasons.size() > 1) {
            tvMeta.setText(String.format(Locale.getDefault(), "%d 个分组 · 当前 %s", seasons.size(), season.seasonTitle));
        }

        if (seasonTabAdapter != null) {
            seasonTabAdapter.notifyDataSetChanged();
        }

        // 如果该季度的剧集尚未抓取，触发异步读取
        if (season.episodes == null || season.episodes.isEmpty()) {
            btnResume.setOnClickListener(null);
            episodeGridAdapter.notifyDataSetChanged();
            btnResume.setText("正在加载剧集清单...");
            QuarkTvClient.getInstance().getEpisodes(season.folderFid, season.path, new QuarkTvClient.Callback<List<QuarkTvClient.CloudEpisode>>() {
                @Override
                public void onSuccess(List<QuarkTvClient.CloudEpisode> result) {
                    season.episodes = result;
                    if (!isShowing() || seasons.get(currentSeasonIndex) != season) return;
                    updateUIWithEpisodes(season);
                }

                @Override
                public void onError(String errorMsg) {
                    if (!isShowing() || seasons.get(currentSeasonIndex) != season) return;
                    btnResume.setText("加载失败，请切换分组重试");
                    Toast.makeText(getContext(), "获取剧集失败：" + errorMsg, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            updateUIWithEpisodes(season);
        }
    }

    private void updateUIWithEpisodes(SeasonData season) {
        if (season.episodes == null || season.episodes.isEmpty()) {
            btnResume.setText("暂无可用剧集");
            tvMeta.setText("暂无剧集 · 4K 原画");
            if (episodeGridAdapter != null) episodeGridAdapter.notifyDataSetChanged();
            return;
        }

        tvMeta.setText(String.format(Locale.getDefault(), "%s · 共 %d 集 · 4K 原画", season.seasonTitle, season.episodes.size()));

        String seasonKey = seriesTitle + "_s" + season.seasonNumber;
        int lastEpNum = prefs.getInt("last_ep_" + seasonKey, season.episodes.get(0).episodeNumber);
        long lastPosMs = prefs.getLong("pos_" + seasonKey + "_" + lastEpNum, 0);

        int resumeIdx = 0;
        for (int i = 0; i < season.episodes.size(); i++) {
            if (season.episodes.get(i).episodeNumber == lastEpNum) {
                resumeIdx = i;
                break;
            }
        }

        final int targetResumeIndex = resumeIdx;
        QuarkTvClient.CloudEpisode targetEp = season.episodes.get(targetResumeIndex);
        String epDisplayName = (targetEp != null && targetEp.name != null && !targetEp.name.isEmpty()) ? targetEp.name : String.format(Locale.getDefault(), "第 %02d 集", lastEpNum);

        String resumeText = (seasons.size() > 1)
                ? String.format(Locale.getDefault(), "▶ 继续播放 %s · %s", season.seasonTitle, epDisplayName)
                : String.format(Locale.getDefault(), "▶ 继续播放 %s", epDisplayName);

        if (lastPosMs > 10000) {
            long totalSeconds = lastPosMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            resumeText += String.format(Locale.getDefault(), " (看到 %02d:%02d)", minutes, seconds);
        }

        btnResume.setText(resumeText);
        btnResume.setOnClickListener(v -> {
            dismiss();
            if (seasonListener != null) {
                prefs.edit().putInt("last_season_" + seriesTitle, season.seasonNumber).apply();
                seasonListener.onSeasonEpisodeSelected(season, targetResumeIndex);
            }
        });

        if (episodeGridAdapter != null) {
            episodeGridAdapter.notifyDataSetChanged();
        }
        if (seasonTabAdapter != null) {
            seasonTabAdapter.notifyDataSetChanged();
        }
        btnResume.requestFocus();
    }

    // 分季标签页 Adapter
    private class SeasonTabAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextSize(14f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(32, 16, 32, 16);
            tv.setFocusable(true);
            tv.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(10, 0, 10, 0);
            tv.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(tv) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            SeasonData s = seasons.get(position);
            TextView tv = (TextView) holder.itemView;
            boolean isCurrent = (position == currentSeasonIndex);

            tv.setText(s.seasonTitle);
            tv.setBackgroundResource(isCurrent ? R.drawable.bg_player_pill_active : R.drawable.bg_player_pill);
            tv.setTextColor(isCurrent ? 0xFFFFFFFF : 0xCCFFFFFF);

            tv.setOnFocusChangeListener((v, hasFocus) -> {
                tv.setTextColor(hasFocus ? 0xFF000000 : (holder.getBindingAdapterPosition() == currentSeasonIndex ? 0xFFFFFFFF : 0xCCFFFFFF));
                v.animate().scaleX(hasFocus ? 1.06f : 1.0f).scaleY(hasFocus ? 1.06f : 1.0f).setDuration(140).start();
            });

            tv.setOnClickListener(v -> {
                int index = holder.getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION && index != currentSeasonIndex) {
                    currentSeasonIndex = index;
                    loadCurrentSeasonData();
                }
            });
        }

        @Override
        public int getItemCount() {
            return seasons.size();
        }
    }

    // 剧集网格 Adapter
    private class EpisodeGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_episode, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            SeasonData season = seasons.get(currentSeasonIndex);
            if (season.episodes == null || position >= season.episodes.size()) return;

            QuarkTvClient.CloudEpisode ep = season.episodes.get(position);
            TextView tvNum = holder.itemView.findViewById(R.id.tvEpNumber);

            // 针对综艺、花絮、特辑等非纯数字剧集，优先展示有意义名称（如“01期上”、“先导片”）；标准电视剧展示两位数字序号“01”
            String epLabel;
            if (ep.name != null && (ep.name.contains("期") || ep.name.contains("先导") || ep.name.contains("花絮") || ep.name.contains("特辑") || ep.name.contains("SP"))) {
                epLabel = ep.name;
                tvNum.setTextSize(13f);
            } else if (ep.name != null && !ep.name.trim().isEmpty() && !ep.name.matches("^第\\s*\\d+\\s*集$")) {
                epLabel = ep.name;
                tvNum.setTextSize(13f);
            } else {
                epLabel = String.format(Locale.getDefault(), "%02d", ep.episodeNumber);
                tvNum.setTextSize(17f);
            }
            tvNum.setText(epLabel);

            String seasonKey = seriesTitle + "_s" + season.seasonNumber;
            int lastEpNum = prefs.getInt("last_ep_" + seasonKey, -1);
            boolean isCurrent = (ep.episodeNumber == lastEpNum);

            tvNum.setBackgroundResource(isCurrent ? R.drawable.bg_player_pill_active : R.drawable.bg_player_pill);
            tvNum.setTextColor(isCurrent ? 0xFFFFFFFF : 0xCCFFFFFF);

            tvNum.setOnFocusChangeListener((v, hasFocus) -> {
                tvNum.setTextColor(hasFocus ? 0xFF000000 : (isCurrent ? 0xFFFFFFFF : 0xCCFFFFFF));
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(140).start();
            });

            tvNum.setOnClickListener(v -> {
                dismiss();
                if (seasonListener != null) {
                    prefs.edit().putInt("last_season_" + seriesTitle, season.seasonNumber).apply();
                    seasonListener.onSeasonEpisodeSelected(season, position);
                }
            });
        }

        @Override
        public int getItemCount() {
            if (seasons.isEmpty()) return 0;
            SeasonData season = seasons.get(currentSeasonIndex);
            return (season.episodes != null) ? season.episodes.size() : 0;
        }
    }
}
