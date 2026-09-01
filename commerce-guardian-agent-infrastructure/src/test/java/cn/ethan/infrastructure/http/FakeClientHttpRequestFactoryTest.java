package cn.ethan.infrastructure.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 类型职责：在不创建网络监听端口的情况下驱动 RestClient 协议测试。
 *
 * @author ethan
 * @date 2026-08-27
 */
public final class FakeClientHttpRequestFactoryTest implements ClientHttpRequestFactory {

    private final Responder responder;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    public FakeClientHttpRequestFactoryTest(Responder responder) {
        this.responder = responder == null ? request -> Response.json(500, "{}") : responder;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new ClientHttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            private final Map<String, Object> attributes = new HashMap<>();

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return attributes;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public OutputStream getBody() {
                return body;
            }

            @Override
            public ClientHttpResponse execute() throws IOException {
                RecordedRequest request = new RecordedRequest(method, uri,
                        HttpHeaders.readOnlyHttpHeaders(new HttpHeaders(headers)), body.toByteArray());
                requests.add(request);
                Response response = responder.respond(request);
                if (response == null) {
                    throw new IOException("fake transport returned no response");
                }
                HttpHeaders responseHeaders = response.headers() == null
                        ? new HttpHeaders() : new HttpHeaders(response.headers());
                if (responseHeaders.getFirst(HttpHeaders.CONTENT_TYPE) == null) {
                    responseHeaders.setContentType(MediaType.APPLICATION_JSON);
                }
                return new FakeClientHttpResponse(response.status(), response.body(), responseHeaders);
            }
        };
    }

    @FunctionalInterface
    public interface Responder {
        Response respond(RecordedRequest request) throws IOException;
    }

    public record RecordedRequest(
            HttpMethod method,
            URI uri,
            HttpHeaders headers,
            byte[] body
    ) {
        public String bodyText() {
            return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        }
    }

    public record Response(int status, String body, HttpHeaders headers) {
        public static Response json(int status, String body) {
            return new Response(status, body, null);
        }
    }

    private static final class FakeClientHttpResponse implements ClientHttpResponse {
        private final int status;
        private final byte[] body;
        private final HttpHeaders headers;

        private FakeClientHttpResponse(int status, String body, HttpHeaders headers) {
            this.status = status;
            this.body = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(status);
        }

        @Override
        public String getStatusText() {
            return Integer.toString(status);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            // 内存响应没有需要释放的资源。
        }
    }
}
