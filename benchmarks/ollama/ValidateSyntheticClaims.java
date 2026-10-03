import com.gtrainer.AnalysisClaims;
import com.gtrainer.AnalysisInput;
import com.gtrainer.AnalysisObservation;
import com.gtrainer.AnalysisSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Operator-only local replay against the unchanged built Kotlin validator. No inference. */
public final class ValidateSyntheticClaims {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].equals("--synthetic-only")) {
            throw new IllegalArgumentException("Two synthetic files and explicit acknowledgement required");
        }
        Path inputPath = Path.of(args[0]);
        Path claimsPath = Path.of(args[1]);
        if (Files.size(inputPath) > 1048576 || Files.size(claimsPath) > 32768) {
            throw new IllegalArgumentException("Bounded synthetic files required");
        }
        AnalysisClaims validator = AnalysisClaims.INSTANCE;
        if (!validator.hash(validator.getSystem()).equals(
                "9e544322e7eb0c976dbcef4c2973b3b0859fb5e5505701d3f76c549ca558e314")) {
            throw new IllegalStateException("Replay requires the unchanged baseline contract");
        }
        AnalysisInput input = validator.getJson().decodeFromString(AnalysisInput.Companion.serializer(),
                Files.readString(inputPath, StandardCharsets.UTF_8));
        AnalysisSnapshot snapshot = validator.prepare(input);
        List<AnalysisObservation> rendered = validator.validateAndRender(
                Files.readString(claimsPath, StandardCharsets.UTF_8), snapshot);
        System.out.println("{\"accepted\":" + (rendered != null && validator.sufficient(snapshot))
                + ",\"packet_sha256\":\"" + snapshot.getPacketSha256()
                + "\",\"observation_count\":" + (rendered == null ? 0 : rendered.size()) + "}");
    }
}
