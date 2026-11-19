import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;

class ProxyProbe {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newHttpClient();
        var executor = Executors.newFixedThreadPool(16);
        var work = new ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 3000; i++) {
            int index = i;
            work.add(executor.submit(() -> {
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + args[0] + "/simulation/" + index))
                        .timeout(Duration.ofSeconds(3)).PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
                    int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                    return status == 200 ? "OK" : "STATUS " + status;
                } catch (Exception ex) {
                    return ex.getClass().getName() + ": " + ex.getMessage();
                }
            }));
        }
        var totals = new java.util.TreeMap<String, Integer>();
        for (var future : work) totals.merge(future.get(), 1, Integer::sum);
        executor.shutdown();
        totals.forEach((key, value) -> System.out.println(value + " " + key));
    }
}
