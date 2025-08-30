import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;

/**
 * Recover.java (generator) - no external libs
 * Usage:
 *   javac Recover.java
 *   java Recover secret.json
 * Output:
 *   prints shares and writes generated_shares.json
 */
public class Recover {
    static SecureRandom rnd = new SecureRandom();

    // Minimal JSON helpers (regex-based) to extract "value", "n", "k" from secret.json
    static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }

    static String findStringOrNumberField(String json, String fieldName) {
        // matches "fieldName": "value"  OR  "fieldName": 12345
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(?:\"([^\"]+)\"|([0-9]+))", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String s = m.group(1);
            if (s != null) return s;
            return m.group(2);
        }
        return null;
    }

    static int findIntField(String json, String fieldName, int defaultVal) {
        String s = findStringOrNumberField(json, fieldName);
        if (s == null) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    static BigInteger findBigIntegerField(String json, String fieldName, BigInteger defaultVal) {
        String s = findStringOrNumberField(json, fieldName);
        if (s == null) return defaultVal;
        try {
            return new BigInteger(s);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    static List<BigInteger> randomCoefficients(BigInteger secret, int k) {
        List<BigInteger> coeffs = new ArrayList<>();
        coeffs.add(secret);
        for (int i = 1; i < k; i++) {
            coeffs.add(BigInteger.valueOf(rnd.nextInt(1000)));
        }
        return coeffs;
    }

    static BigInteger evalPoly(List<BigInteger> coeffs, int x) {
        BigInteger res = BigInteger.ZERO;
        BigInteger bx = BigInteger.ONE;
        BigInteger bxVal = BigInteger.valueOf(x);
        for (BigInteger c : coeffs) {
            res = res.add(c.multiply(bx));
            bx = bx.multiply(bxVal);
        }
        return res;
    }

    static String toJsonArrayOfShares(List<Map<String,String>> shares) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < shares.size(); i++) {
            Map<String,String> s = shares.get(i);
            sb.append("  {\n");
            sb.append("    \"x\": ").append(s.get("x")).append(",\n");
            sb.append("    \"base\": \"").append(s.get("base")).append("\",\n");
            sb.append("    \"value\": \"").append(s.get("value")).append("\"\n");
            sb.append("  }");
            if (i < shares.size()-1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    public static void main(String[] args) {
        String path = "secret.json";
        if (args.length > 0) path = args[0];

        try {
            String json = readFile(path);
            BigInteger secret = findBigIntegerField(json, "value", BigInteger.ZERO);
            int n = findIntField(json, "n", 5);
            int k = findIntField(json, "k", 3);

            System.out.println("Secret (value): " + secret);
            System.out.println("n = " + n + ", k = " + k);

            List<BigInteger> coeffs = randomCoefficients(secret, k);
            List<Map<String,String>> shares = new ArrayList<>();

            for (int i = 1; i <= n; i++) {
                BigInteger y = evalPoly(coeffs, i);
                Map<String,String> s = new LinkedHashMap<>();
                s.put("x", Integer.toString(i));
                s.put("base", "10");
                s.put("value", y.toString());
                shares.add(s);
            }

            String out = toJsonArrayOfShares(shares);
            Files.write(Paths.get("generated_shares.json"), out.getBytes());
            System.out.println("Generated shares (written to generated_shares.json):");
            System.out.println(out);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
