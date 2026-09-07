package com.fongmi.android.tv.player.exo;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Timeout;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/**
 * 播放器数据源字节统计：包装 OkHttp Call.Factory，对响应体读取计数并上报 DurationProbe。
 * 仅统计"实际从网络读走的字节"，用于无时长流的码率采样（估算总时长）。
 */
public class CountingCallFactory implements Call.Factory {

    private final Call.Factory delegate;

    public CountingCallFactory(Call.Factory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Call newCall(Request request) {
        return new CountingCall(delegate.newCall(request));
    }

    static class CountingCall implements Call {

        private final Call delegate;

        CountingCall(Call delegate) {
            this.delegate = delegate;
        }

        @Override
        public Request request() {
            return delegate.request();
        }

        @Override
        public Response execute() throws IOException {
            Response response = delegate.execute();
            ResponseBody body = response.body();
            if (body != null) response = response.newBuilder().body(new CountingBody(body)).build();
            return response;
        }

        @Override
        public void enqueue(Callback callback) {
            delegate.enqueue(callback);
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public boolean isExecuted() {
            return delegate.isExecuted();
        }

        @Override
        public boolean isCanceled() {
            return delegate.isCanceled();
        }

        @Override
        public Timeout timeout() {
            return delegate.timeout();
        }

        @Override
        public Call clone() {
            return new CountingCall(delegate.clone());
        }

        @Override
        public OkHttpClient client() {
            return delegate.client();
        }
    }

    static class CountingBody extends ResponseBody {

        private final ResponseBody delegate;
        private final AtomicReference<BufferedSource> wrapped = new AtomicReference<>();

        CountingBody(ResponseBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public okhttp3.MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            return wrapped.updateAndGet(current -> current != null ? current : Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long read = super.read(sink, byteCount);
                    if (read > 0) DurationProbe.onBytes(read);
                    return read;
                }
            }));
        }
    }
}
