import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container-only check; intentionally does not print response bodies or credentials. */
public class HealthProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) System.exit(2);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + args[0] + "/actuator/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            var reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() == 200 && reply.body().contains("\"status\":\"UP\"")) return;
        } catch (Exception ignored) {
            // The Compose health result is sufficient; avoid printing internal URLs.
        }
        System.exit(1);
    }
}
