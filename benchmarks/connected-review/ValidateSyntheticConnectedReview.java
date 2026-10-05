import com.gtrainer.InterpretationContractV1;
import com.gtrainer.InterpretationPacketV1;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kotlinx.serialization.json.Json;

/** Local operator replay against the actual Kotlin decoder. Performs no inference. */
public final class ValidateSyntheticConnectedReview {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].equals("--synthetic-only")) {
            throw new IllegalArgumentException("Packet, raw response, and --synthetic-only required");
        }
        Path packetPath = Path.of(args[0]);
        Path responsePath = Path.of(args[1]);
        if (Files.size(packetPath) > 32768 || Files.size(responsePath) > 65536) {
            throw new IllegalArgumentException("Bounded synthetic input files required");
        }
        InterpretationPacketV1 packet = Json.Default.decodeFromString(
            InterpretationPacketV1.Companion.serializer(), Files.readString(packetPath, StandardCharsets.UTF_8));
        try {
            InterpretationContractV1.INSTANCE.decodeDraft(Files.readString(responsePath, StandardCharsets.UTF_8), packet);
            System.out.println("{\"accepted\":true}");
        } catch (IllegalArgumentException error) {
            System.out.println("{\"accepted\":false}");
        }
    }
}
