package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SnapshotCodec}.
 *
 * <p>Verifies codec contract and default implementations.
 */
@DisplayName("SnapshotCodec Tests")
class SnapshotCodecTest {

    /** Test implementation of SnapshotCodec for testing. */
    static class TestSnapshotCodec implements SnapshotCodec<String> {

        @Override
        public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] data) {
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    }

    @Test
    @DisplayName("Should encode and decode correctly")
    void codec_encodesAndDecodes() throws Exception {
        var codec = new TestSnapshotCodec();
        String original = "Hello, World!";

        byte[] encoded = codec.encode(original);
        String decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("Should handle empty strings")
    void codec_handlesEmptyStrings() throws Exception {
        var codec = new TestSnapshotCodec();
        String empty = "";

        byte[] encoded = codec.encode(empty);
        String decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(empty);
        assertThat(encoded).isEmpty();
    }

    @Test
    @DisplayName("Should handle special characters")
    void codec_handlesSpecialCharacters() throws Exception {
        var codec = new TestSnapshotCodec();
        String special = "Test with unicode: \u00E9, \u4E2D\u6587, and emojis: \uD83D\uDE00";

        byte[] encoded = codec.encode(special);
        String decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(special);
    }

    @Test
    @DisplayName("Should throw on encode null")
    void encode_throwsOnNull() {
        var codec = new TestSnapshotCodec();

        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw on decode null")
    void decode_throwsOnNull() {
        var codec = new TestSnapshotCodec();

        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw on decode empty array")
    void decode_throwsOnEmptyArray() {
        var codec = new TestSnapshotCodec();

        assertThatThrownBy(() -> codec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should return content type")
    void contentType_returnsType() {
        var codec = new TestSnapshotCodec();

        assertThat(codec.contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("Should check canDecode for valid data")
    void canDecode_returnsTrueForValidData() {
        var codec = new TestSnapshotCodec();
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        assertThat(codec.canDecode(data)).isTrue();
    }

    @Test
    @DisplayName("Should check canDecode for null data")
    void canDecode_returnsFalseForNull() {
        var codec = new TestSnapshotCodec();

        assertThat(codec.canDecode(null)).isFalse();
    }

    @Test
    @DisplayName("Should check canDecode for empty data")
    void canDecode_returnsFalseForEmpty() {
        var codec = new TestSnapshotCodec();

        assertThat(codec.canDecode(new byte[0])).isFalse();
    }

    @Test
    @DisplayName("Should propagate codec exceptions")
    void codec_propagatesExceptions() {
        var failingCodec =
                new SnapshotCodec<String>() {
                    @Override
                    public byte[] encode(String value) throws Exception {
                        throw new Exception("Encode failed");
                    }

                    @Override
                    public String decode(byte[] data) throws Exception {
                        throw new Exception("Decode failed");
                    }

                    @Override
                    public String contentType() {
                        return "application/test";
                    }
                };

        assertThatThrownBy(() -> failingCodec.encode("test"))
                .isInstanceOf(Exception.class)
                .hasMessage("Encode failed");

        assertThatThrownBy(() -> failingCodec.decode("test".getBytes()))
                .isInstanceOf(Exception.class)
                .hasMessage("Decode failed");
    }

    @Test
    @DisplayName("CodecException should wrap errors")
    void codecException_wrapsErrors() {
        var cause = new RuntimeException("Underlying error");
        var codecException = new SnapshotCodec.CodecException("Codec failed", cause);

        assertThat(codecException.getMessage()).isEqualTo("Codec failed");
        assertThat(codecException.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("CodecException should support message only")
    void codecException_supportsMessageOnly() {
        var codecException = new SnapshotCodec.CodecException("Codec failed");

        assertThat(codecException.getMessage()).isEqualTo("Codec failed");
        assertThat(codecException.getCause()).isNull();
    }

    @Test
    @DisplayName("CodecException should support cause only")
    void codecException_supportsCauseOnly() {
        var cause = new RuntimeException("Underlying error");
        var codecException = new SnapshotCodec.CodecException(cause);

        assertThat(codecException.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should handle large data")
    void codec_handlesLargeData() throws Exception {
        var codec = new TestSnapshotCodec();
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            largeData.append("x");
        }
        String largeString = largeData.toString();

        byte[] encoded = codec.encode(largeString);
        String decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(largeString);
    }

    @Test
    @DisplayName("Should preserve byte-level precision")
    void codec_preservesBytePrecision() throws Exception {
        var codec = new TestSnapshotCodec();
        byte[] originalBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            originalBytes[i] = (byte) i;
        }
        String original = new String(originalBytes, StandardCharsets.ISO_8859_1);

        byte[] encoded = codec.encode(original);
        String decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("Should handle multiple encode/decode cycles")
    void codec_handlesMultipleCycles() throws Exception {
        var codec = new TestSnapshotCodec();
        String original = "Test data for multiple cycles";

        String result = original;
        for (int i = 0; i < 10; i++) {
            byte[] encoded = codec.encode(result);
            result = codec.decode(encoded);
        }

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("Should handle different content types")
    void codec_supportsDifferentContentTypes() {
        var jsonCodec =
                new SnapshotCodec<Object>() {
                    @Override
                    public byte[] encode(Object value) {
                        return ("{\"value\":" + value + "}").getBytes();
                    }

                    @Override
                    public Object decode(byte[] data) {
                        return new String(data);
                    }

                    @Override
                    public String contentType() {
                        return "application/json";
                    }
                };

        assertThat(jsonCodec.contentType()).isEqualTo("application/json");
    }
}
