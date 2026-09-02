package com.fongmi.android.tv.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

public class MediaSourceFactory implements MediaSource.Factory {

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;
    private static SimpleCache cache;

    public MediaSourceFactory() {
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        getHttpDataSourceFactory().setDefaultRequestProperties(ExoUtil.extractHeaders(mediaItem));
        String url = mediaItem.requestMetadata.mediaUri != null ? mediaItem.requestMetadata.mediaUri.toString() : "";
        if (url.contains("***") && url.contains("|||")) return createConcatenatingMediaSource(mediaItem, url);
        if (url.startsWith("rtsp://")) return createRtspMediaSource(mediaItem);
        if (isDashUrl(url)) return createDashMediaSource(mediaItem);
        else return defaultMediaSourceFactory.createMediaSource(mediaItem);
    }

    private boolean isDashUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains("type=mpd") || lower.endsWith(".mpd") || lower.contains(".mpd?") || lower.contains(".mpd&");
    }

    private MediaSource createDashMediaSource(MediaItem mediaItem) {
        MediaItem item = mediaItem.buildUpon().setMimeType(MimeTypes.APPLICATION_MPD).build();
        return new DashMediaSource.Factory(getDataSourceFactory())
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3))
                .createMediaSource(item);
    }

    private MediaSource createRtspMediaSource(MediaItem mediaItem) {
        RtspMediaSource.Factory factory = new RtspMediaSource.Factory()
                .setDebugLoggingEnabled(true)
                .setForceUseRtpTcp(false)
                .setTimeoutMs(15000)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));
        
        // 对于 .sdp 文件，确保正确处理
        MediaItem.Builder builder = mediaItem.buildUpon();
        String url = mediaItem.requestMetadata.mediaUri.toString();
        if (url.toLowerCase().endsWith(".sdp")) {
            builder.setMimeType(MimeTypes.APPLICATION_RTSP);
        }
        
        return factory.createMediaSource(builder.build());
    }

    private MediaSource createConcatenatingMediaSource(MediaItem mediaItem, String url) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        for (String split : url.split("\\*\\*\\*")) {
            String[] info = split.split("\\|\\|\\|");
            if (info.length >= 2) {
                MediaItem item = mediaItem.buildUpon().setUri(Uri.parse(info[0])).build();
                if (info[0].startsWith("rtsp://")) {
                    builder.add(createRtspMediaSource(item), Long.parseLong(info[1]));
                } else {
                    builder.add(defaultMediaSourceFactory.createMediaSource(item), Long.parseLong(info[1]));
                }
            }
        }
        return builder.build();
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) {
            DataSource.Factory cached = getCacheDataSource(
                    new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory()));
            // 改写层必须放在缓存层之外：若置于其内，第二次播放会直接命中缓存里
            // 未经改写的 m3u8，删除逻辑根本不会被触发。
            dataSourceFactory = new HlsAdStrippingDataSource.Factory(cached);
        }
        return dataSourceFactory;
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        return new CacheDataSource.Factory().setCache(getCache()).setUpstreamDataSourceFactory(upstreamFactory).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.player());
        return httpDataSourceFactory;
    }

    private static SimpleCache getCache() {
        if (cache == null) cache = new SimpleCache(Path.exo(), new NoOpCacheEvictor(), new StandaloneDatabaseProvider(App.get()));
        return cache;
    }
}
