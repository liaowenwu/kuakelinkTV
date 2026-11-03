package com.gathin.tvhome.cloud.quark;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.CloudMovie;
import com.gathin.tvhome.cloud.quark.QuarkTvClient.QuarkFile;

public class CinemaFolderTest {
    private final QuarkFile folder = new QuarkFile("series", "anime", "凡人修仙传", 0, true, "", "");
    private final String path = "/资源库/动漫/凡人修仙传";

    private QuarkFile video(String id, String name) {
        return new QuarkFile(id, "series", name, 1024, false, "", "");
    }

    @Test public void mixedFolderKeepsMainEpisodesAndEveryVersionSeparate() {
        List<CloudMovie> children = Arrays.asList(
                new CloudMovie("特别篇", "", path + "/特别篇", "special", true, ""),
                new CloudMovie("其他版本/第一季", "", path + "/其他版本/第一季", "version", true, ""));
        CloudMovie result = QuarkTvClient.describeFolder(path, folder, Arrays.asList(
                video("ep1", "凡人修仙传 第001集.mp4"), video("ep2", "凡人修仙传 第002集.mp4")), children, "");
        assertEquals(CloudMovie.TYPE_COLLECTION_SEASONS, result.itemType);
        assertEquals(3, result.subItems.size());
        assertEquals("正片", result.subItems.get(0).title);
        assertEquals("series", result.subItems.get(0).fid);
        assertEquals(path, result.subItems.get(0).path);
        assertEquals("special", result.subItems.get(1).fid);
        assertEquals("其他版本/第一季", result.subItems.get(2).title);
        assertEquals("version", result.subItems.get(2).fid);
    }

    @Test public void oneVideoAlongsideSpecialsIsStillASelectableMainGroup() {
        CloudMovie special = new CloudMovie("特别篇", "", path + "/特别篇", "special", true, "");
        CloudMovie result = QuarkTvClient.describeFolder(path, folder,
                Collections.singletonList(video("ep1", "第001集.mp4")), Collections.singletonList(special), "");
        assertEquals(2, result.subItems.size());
        assertTrue(result.subItems.get(0).isDir);
        assertEquals("series", result.subItems.get(0).fid);
    }

    @Test public void subdirectoriesOnlyDoNotInventAnEmptyMainGroup() {
        CloudMovie special = new CloudMovie("特别篇", "", path + "/特别篇", "special", true, "");
        CloudMovie result = QuarkTvClient.describeFolder(path, folder, Collections.emptyList(),
                Collections.singletonList(special), "");
        assertEquals(1, result.subItems.size());
        assertEquals("特别篇", result.subItems.get(0).title);
    }

    @Test public void singleMovieKeepsDirectPlayback() {
        CloudMovie result = QuarkTvClient.describeFolder(path, folder,
                Collections.singletonList(video("movie", "影片.mp4")), Collections.emptyList(), "");
        assertFalse(result.isDir);
        assertEquals("movie", result.fid);
        assertEquals(path + "/影片.mp4", result.path);
    }

    @Test public void nonVideoAndIgnoredFilesDoNotCreateAnEmptyWork() {
        CloudMovie result = QuarkTvClient.describeFolder(path, folder, Arrays.asList(
                video("poster", "海报.jpg"), video("subtitle", "字幕.srt"),
                video("trailer", "预告.mp4")), Collections.emptyList(), "");
        assertNull(result);
    }
}
