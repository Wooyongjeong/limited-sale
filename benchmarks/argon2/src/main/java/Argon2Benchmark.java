import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2Benchmark {
    private static final int WARMUP = 30;
    private static final int SAMPLES = 200;
    private static final String INPUT = "synthetic-benchmark-input-42";

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Java 21 is required");
        }
        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        String encoded = encoder.encode(INPUT);
        if (!encoded.startsWith("$argon2id$v=19$m=19456,t=2,p=1$")
                || !encoder.matches(INPUT, encoded) || encoder.matches("incorrect-synthetic-input", encoded)) {
            throw new IllegalStateException("Argon2id parameters or match behavior failed");
        }

        for (int i = 0; i < WARMUP; i++) {
            encoder.encode(INPUT);
            if (!encoder.matches(INPUT, encoded)) {
                throw new IllegalStateException("Warmup match failed");
            }
        }
        long[] encodes = new long[SAMPLES];
        long[] matches = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            String result = encoder.encode(INPUT);
            encodes[i] = System.nanoTime() - start;
            if (result.isEmpty()) {
                throw new IllegalStateException("Empty hash");
            }
            start = System.nanoTime();
            boolean match = encoder.matches(INPUT, encoded);
            matches[i] = System.nanoTime() - start;
            if (!match) {
                throw new IllegalStateException("Measured match failed");
            }
        }
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("vm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.vendor"));
        System.out.println("jvm_args=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        System.out.println("parameters=argon2id,m=19456KiB,t=2,p=1,salt=16B,hash=32B");
        System.out.println("warmup_each=" + WARMUP + ", samples_each=" + SAMPLES);
        print("encode", encodes);
        print("matches", matches);
    }

    private static void print(String name, long[] values) {
        Arrays.sort(values);
        System.out.printf(Locale.ROOT, "%s ms: p50=%.3f p95=%.3f min=%.3f max=%.3f%n", name,
                percentile(values, 0.50) / 1_000_000.0, percentile(values, 0.95) / 1_000_000.0,
                values[0] / 1_000_000.0, values[values.length - 1] / 1_000_000.0);
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(percentile * sorted.length) - 1];
    }
}
