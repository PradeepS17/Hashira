import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Hashira.java (recovery + suspicion detection) - no external libs
 * Usage:
 *   javac Hashira.java
 *   java Hashira firstcase.json secondcase.json
 *
 * If no args given, it defaults to firstcase.json and secondcase.json.
 */
public class Hashira {

    // ----------------- Rational (exact) -----------------
    static class Rational {
        BigInteger num;
        BigInteger den;
        Rational(BigInteger n, BigInteger d) {
            if (d.signum() == 0) throw new ArithmeticException("zero denominator");
            if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
            BigInteger g = n.gcd(d);
            if (!g.equals(BigInteger.ONE)) {
                n = n.divide(g); d = d.divide(g);
            }
            num = n; den = d;
        }
        static Rational of(BigInteger n) { return new Rational(n, BigInteger.ONE); }
        static Rational zero() { return new Rational(BigInteger.ZERO, BigInteger.ONE); }
        static Rational one() { return new Rational(BigInteger.ONE, BigInteger.ONE); }

        Rational add(Rational o) {
            BigInteger n = this.num.multiply(o.den).add(o.num.multiply(this.den));
            BigInteger d = this.den.multiply(o.den);
            return new Rational(n, d);
        }
        Rational multiply(Rational o) {
            return new Rational(this.num.multiply(o.num), this.den.multiply(o.den));
        }
        BigInteger toBigIntegerExact() {
            BigInteger[] qr = num.divideAndRemainder(den);
            if (!qr[1].equals(BigInteger.ZERO)) throw new ArithmeticException("not integer: " + num + "/" + den);
            return qr[0];
        }
        public String toString() { return num + "/" + den; }
    }

    static class Share {
        int x;
        BigInteger y;
        Share(int x, BigInteger y) { this.x = x; this.y = y; }
        public String toString() { return "(" + x + "," + y + ")"; }
    }

    static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }

    // --------- Small JSON parsers using regex (handles expected simple structures) ---------
    static String findStringOrNumberField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(?:\"([^\"]+)\"|([0-9]+))", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            if (m.group(1) != null) return m.group(1);
            return m.group(2);
        }
        return null;
    }

    static int findIntField(String json, String fieldName, int defaultVal) {
        String s = findStringOrNumberField(json, fieldName);
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s); } catch(Exception e) { return defaultVal; }
    }

    // Parse format A: "1": {"base":"10","value":"4"}, ... and keys.k
    static class ParsedFile {
        List<Share> shares;
        int k;
        ParsedFile(List<Share> s, int k) { this.shares = s; this.k = k; }
    }

    static ParsedFile parseSharesFromFile(String filename) throws IOException {
        String json = readFile(filename);

        // If file contains direct value (Format B), return empty shares and k (we'll just print it)
        if (json.contains("\"value\"") && json.indexOf("\"keys\"") == -1) {
            String v = findStringOrNumberField(json, "value");
            System.out.println(filename + " contains direct 'value': " + v);
            int k = findIntField(json, "k", 0);
            return new ParsedFile(new ArrayList<>(), k);
        }

        // else expect "keys": { "n":..., "k":... }
        int k = findIntField(json, "k", 0);
        if (json.contains("\"keys\"")) {
            // try to find keys.k inside keys object
            Pattern pk = Pattern.compile("\"keys\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
            Matcher mk = pk.matcher(json);
            if (mk.find()) {
                String inside = mk.group(1);
                int kk = findIntField(inside, "k", k);
                k = kk;
            }
        }

        // find all numbered entries like "1": { "base": "10", "value": "4" }
        List<Share> shares = new ArrayList<>();
        Pattern pShare = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        Matcher m = pShare.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String body = m.group(2);
            String base = findStringOrNumberField(body, "base");
            String value = findStringOrNumberField(body, "value");
            if (base != null && value != null) {
                try {
                    int x = Integer.parseInt(key);
                    BigInteger y = new BigInteger(value, Integer.parseInt(base));
                    shares.add(new Share(x, y));
                } catch (Exception e) {
                    System.err.println("Skipping share " + key + " due to parse error: " + e.getMessage());
                }
            }
        }

        // sort by x
        shares.sort(Comparator.comparingInt(s -> s.x));
        return new ParsedFile(shares, k);
    }

    // ------------ Lagrange exact interpolation (constant term) ------------
    static BigInteger reconstructSecretExact(List<Share> shares) {
        int k = shares.size();
        Rational secret = Rational.zero();

        for (int i = 0; i < k; i++) {
            BigInteger xi = BigInteger.valueOf(shares.get(i).x);
            BigInteger yi = shares.get(i).y;
            Rational li = Rational.one();
            for (int j = 0; j < k; j++) {
                if (i == j) continue;
                BigInteger xj = BigInteger.valueOf(shares.get(j).x);
                // li *= (-xj) / (xi - xj)
                Rational term = new Rational(xj.negate(), xi.subtract(xj));
                li = li.multiply(term);
            }
            secret = secret.add(li.multiply(Rational.of(yi)));
        }

        return secret.toBigIntegerExact();
    }

    // combinations (indices)
    static void combineRec(int n, int k, int start, LinkedList<Integer> cur, List<List<Integer>> out) {
        if (cur.size() == k) { out.add(new ArrayList<>(cur)); return; }
        for (int i = start; i < n; i++) {
            cur.add(i); combineRec(n, k, i+1, cur, out); cur.removeLast();
        }
    }

    static List<List<Integer>> allCombinations(int n, int k) {
        List<List<Integer>> out = new ArrayList<>();
        combineRec(n, k, 0, new LinkedList<>(), out);
        return out;
    }

    static Map<BigInteger,Integer> candidateSecrets(List<Share> shares, int k, List<List<Integer>> combos) {
        Map<BigInteger,Integer> freq = new HashMap<>();
        for (List<Integer> comb : combos) {
            List<Share> chosen = new ArrayList<>();
            for (int idx : comb) chosen.add(shares.get(idx));
            try {
                BigInteger s = reconstructSecretExact(chosen);
                freq.put(s, freq.getOrDefault(s,0)+1);
            } catch (Exception e) {
                // skip combos that don't give integer secret
            }
        }
        return freq;
    }

    // detect suspicious shares by majority-support heuristic
    static List<Integer> detectBadShares(List<Share> shares, int k) {
        int n = shares.size();
        List<List<Integer>> combos = allCombinations(n, k);
        Map<BigInteger,Integer> freq = candidateSecrets(shares, k, combos);
        if (freq.isEmpty()) return Collections.emptyList();

        BigInteger majority = null;
        int maxf = -1;
        for (Map.Entry<BigInteger,Integer> e : freq.entrySet()) {
            if (e.getValue() > maxf) { maxf = e.getValue(); majority = e.getKey(); }
        }

        Map<Integer,Integer> support = new HashMap<>();
        for (int i = 0; i < n; i++) support.put(i, 0);

        for (List<Integer> comb : combos) {
            List<Share> chosen = new ArrayList<>();
            for (int idx : comb) chosen.add(shares.get(idx));
            try {
                BigInteger s = reconstructSecretExact(chosen);
                if (s.equals(majority)) {
                    for (int idx : comb) support.put(idx, support.get(idx) + 1);
                }
            } catch (Exception e) {}
        }

        // sort indices by support ascending (least-support = most suspicious)
        List<Integer> indices = new ArrayList<>(support.keySet());
        indices.sort(Comparator.comparingInt(support::get));
        return indices;
    }

    static void processFile(String filename) throws IOException {
        System.out.println("Processing: " + filename);
        ParsedFile parsed = parseSharesFromFile(filename);

        if (parsed.shares.isEmpty()) {
            System.out.println("No encoded shares found in file (likely direct 'value' format).");
            return;
        }

        List<Share> shares = parsed.shares;
        int k = parsed.k;
        System.out.println("Loaded shares (x,y): " + shares);
        System.out.println("k = " + k);
        if (shares.size() == k) {
            try {
                BigInteger secret = reconstructSecretExact(shares);
                System.out.println("Recovered secret using provided k shares: " + secret);
                return;
            } catch (Exception e) {
                System.out.println("Unable to reconstruct from provided k shares: " + e.getMessage());
            }
        }

        List<List<Integer>> combos = allCombinations(shares.size(), k);
        Map<BigInteger,Integer> freq = candidateSecrets(shares, k, combos);
        if (freq.isEmpty()) {
            System.out.println("No valid secrets from any combination.");
            return;
        }
        System.out.println("Candidate secrets (value -> frequency):");
        for (Map.Entry<BigInteger,Integer> e : freq.entrySet()) {
            System.out.println("  " + e.getKey() + " -> " + e.getValue());
        }

        // majority secret
        BigInteger majority = null;
        int maxf = -1;
        for (Map.Entry<BigInteger,Integer> e : freq.entrySet()) {
            if (e.getValue() > maxf) { maxf = e.getValue(); majority = e.getKey(); }
        }
        System.out.println("Majority secret: " + majority);

        List<Integer> suspicious = detectBadShares(shares, k);
        if (suspicious.isEmpty()) {
            System.out.println("No suspicious shares detected.");
        } else {
            System.out.println("Shares ordered by suspicion (lowest support first). Format: index(in list) => x");
            for (int idx : suspicious) {
                System.out.println("  idx=" + idx + " => x=" + shares.get(idx).x + "  y=" + shares.get(idx).y);
            }
            int most = suspicious.get(0);
            System.out.println("Most suspicious share: index=" + most + " x=" + shares.get(most).x);
        }
    }

    public static void main(String[] args) {
        String[] files = {"firstcase.json", "secondcase.json"};
        if (args.length > 0) files = args;
        for (String f : files) {
            try {
                if (!Files.exists(Paths.get(f))) {
                    System.out.println("File not found: " + f + " (skipping)");
                    continue;
                }
                processFile(f);
            } catch (Exception e) {
                System.err.println("Error processing " + f + ": " + e.getMessage());
                e.printStackTrace();
            }
            System.out.println("--------------------------------------------------");
        }
    }
}
