package com.fongmi.android.tv.player.exo;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/**
 * 播放器数据源字节统计：给 OkHttpClient 挂载计数拦截器，对响应体实际读取的字节计数并上报 DurationProbe。
 * 仅统计"真实从网络读走的字节"，用于无时长流（TS 直链等）的码率采样（估算总时长）。
 * <p>
 * 用拦截器而非包装 {@link okhttp3.Call.Factory}：okhttp 5.x 的 {@code Call} 接口含
 * 无法在 Java 中引用返回类型的成员，拦截器方案则对 okhttp 3.x/4.x/5.x 全兼容。
 */
public class CountingCallFactory {

    private CountingCallFactory() {
    }

    /** 复制播放器 client 并挂载字节计数拦截器（连接池与原 client 共享，无额外开销） */
    public static OkHttpClient wrap(OkHttpClient client) {
        return client.newBuilder().addInterceptor(new CountingInterceptor()).build();
    }

    static class CountingInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            // 仅在估算激活时包装响应体：直播与常规点播链路原样返回，绝不受影响
            if (!DurationProbe.isActive()) return response;
            ResponseBody body = response.body();
            if (body != null) response = response.newBuilder().body(new CountingBody(body)).build();
            return response;
        }
    }

    static class CountingBody extends ResponseBody {

        private final ResponseBody delegate;
        private BufferedSource wrapped;

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
        public synchronized BufferedSource source() {
            if (wrapped == null) {
                wrapped = Okio.buffer(new ForwardingSource(delegate.source()) {
                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        long read = super.read(sink, byteCount);
                        if (read > 0) DurationProbe.onBytes(read);
                        return read;
                    }
                });
            }
            return wrapped;
        }
    }
}
