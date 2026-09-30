import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the plugin APK against the ATAK APK it will be loaded into.
 *
 * <p>Plugin classes are loaded parent-first, so ATAK's copy of a class always wins, and ATAK's
 * copies are not what the plugin was compiled against: ATAK's build desugared the default
 * methods of interfaces into abstract ones and removed what it didn't use. Nothing fails until
 * the code runs. This reports, from the two APKs alone:
 *
 * <ul>
 *   <li>plugin classes that implement an interface of ATAK's without implementing all of its
 *       methods (AbstractMethodError);</li>
 *   <li>methods and fields the plugin refers to that ATAK's copy of the class doesn't have
 *       (NoSuchMethodError, NoSuchFieldError);</li>
 *   <li>classes the plugin packages that ATAK's copy will shadow.</li>
 * </ul>
 *
 * <pre>java tools/AtakLinkCheck.java atak.apk plugin.apk [-v]</pre>
 *
 * Run it from the project root. Exits with status 1 if anything of the first two kinds is
 * found that tools/atak-link-ignore.txt doesn't accept.
 */
public class AtakLinkCheck {

    private static final int ACC_INTERFACE = 0x200;
    private static final int ACC_ABSTRACT = 0x400;
    private static final int NO_INDEX = -1;

    private static final class Cls {
        String name;
        String sup;
        int flags;
        final List<String> ifaces = new ArrayList<>();
        /** name + descriptor of a virtual method, to whether it is abstract */
        final Map<String, Boolean> virtuals = new HashMap<>();
        final Set<String> directs = new HashSet<>();
        final Set<String> fields = new HashSet<>();

        boolean isInterface() {
            return (flags & ACC_INTERFACE) != 0;
        }

        boolean isAbstract() {
            return (flags & ACC_ABSTRACT) != 0;
        }
    }

    private static final class App {
        final Map<String, Cls> classes = new HashMap<>();
        /** class, then name + descriptor */
        final Set<List<String>> methodRefs = new LinkedHashSet<>();
        /** class, then name + ":" + type */
        final Set<List<String>> fieldRefs = new LinkedHashSet<>();
    }

    private static App atak;
    private static App plugin;

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AtakLinkCheck atak.apk plugin.apk [-v]");
            System.exit(2);
        }
        final boolean verbose = args.length > 2 && args[2].equals("-v");
        atak = load(args[0]);
        plugin = load(args[1]);
        System.out.println("ATAK: " + atak.classes.size() + " classes, plugin: "
                + plugin.classes.size() + " classes");

        final Map<String, List<String>> abstractMethods = new TreeMap<>();
        final List<String> shadowed = new ArrayList<>();
        for (final Cls c : plugin.classes.values()) {
            if (atak.classes.containsKey(c.name)) {
                shadowed.add(c.name);
                continue;
            }
            if (c.isInterface() || c.isAbstract()) {
                continue;
            }
            final Set<String> ifaces = new LinkedHashSet<>();
            interfacesOf(c, ifaces, new HashSet<>());
            for (final String name : ifaces) {
                final Cls i = atak.classes.get(name);
                if (i == null) {
                    continue;
                }
                for (final Map.Entry<String, Boolean> m : i.virtuals.entrySet()) {
                    if (m.getValue() && !implementedBy(c, m.getKey())) {
                        abstractMethods.computeIfAbsent(name + "->" + m.getKey(),
                                k -> new ArrayList<>()).add(c.name);
                    }
                }
            }
        }

        final Map<String, String> missing = new TreeMap<>();
        for (final List<String> ref : plugin.methodRefs) {
            final Cls c = resolve(ref.get(0));
            if (c != null && touchesAtak(c, new HashSet<>())
                    && Boolean.FALSE.equals(hasMethod(c, ref.get(1), new HashSet<>()))) {
                missing.put(ref.get(0) + "->" + ref.get(1), "method");
            }
        }
        for (final List<String> ref : plugin.fieldRefs) {
            final Cls c = resolve(ref.get(0));
            if (c != null && touchesAtak(c, new HashSet<>())
                    && Boolean.FALSE.equals(hasField(c, ref.get(1), new HashSet<>()))) {
                missing.put(ref.get(0) + "->" + ref.get(1), "field");
            }
        }

        Collections.sort(shadowed);
        System.out.println("\n" + shadowed.size() + " plugin classes shadowed by ATAK's copy"
                + (verbose ? ":" : " (-v lists them)"));
        if (verbose) {
            shadowed.forEach(s -> System.out.println("  " + s));
        }

        final List<String> ignore = ignoreList();
        int ignored = 0;
        for (final List<String> implementers : abstractMethods.values()) {
            final int before = implementers.size();
            implementers.removeIf(c -> ignore.stream().anyMatch(c::contains));
            ignored += before - implementers.size();
        }
        abstractMethods.values().removeIf(List::isEmpty);
        final int before = missing.size();
        missing.keySet().removeIf(k -> ignore.stream().anyMatch(k::contains));
        ignored += before - missing.size();

        System.out.println("\n" + abstractMethods.size()
                + " methods of ATAK's interfaces left unimplemented (AbstractMethodError):");
        for (final Map.Entry<String, List<String>> e : abstractMethods.entrySet()) {
            System.out.println("  " + e.getKey());
            Collections.sort(e.getValue());
            e.getValue().forEach(c -> System.out.println("      by " + c));
        }

        System.out.println("\n" + missing.size()
                + " references to members ATAK's copy doesn't have (NoSuch...Error):");
        missing.forEach((k, v) -> System.out.println("  " + v + " " + k));

        System.out.println("\n" + ignored + " findings accepted by " + IGNORE_FILE);
        System.exit(abstractMethods.isEmpty() && missing.isEmpty() ? 0 : 1);
    }

    private static final String IGNORE_FILE = "tools/atak-link-ignore.txt";

    private static List<String> ignoreList() throws Exception {
        final List<String> out = new ArrayList<>();
        final Path file = Paths.get(IGNORE_FILE);
        if (Files.exists(file)) {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    // --- the class hierarchy as the class loader sees it ---

    /** ATAK's copy first. Null for a class of the platform, about which nothing is known. */
    private static Cls resolve(final String name) {
        final Cls c = atak.classes.get(name);
        return c != null ? c : plugin.classes.get(name);
    }

    private static void interfacesOf(final Cls c, final Set<String> out, final Set<String> seen) {
        if (c == null || !seen.add(c.name)) {
            return;
        }
        for (final String i : c.ifaces) {
            out.add(i);
            interfacesOf(resolve(i), out, seen);
        }
        if (c.sup != null) {
            interfacesOf(resolve(c.sup), out, seen);
        }
    }

    /** Whether the class or a superclass has a body for the method. A platform class may. */
    private static boolean implementedBy(Cls c, final String method) {
        while (c != null) {
            if (Boolean.FALSE.equals(c.virtuals.get(method))) {
                return true;
            }
            if (c.sup == null) {
                return false;
            }
            final Cls s = resolve(c.sup);
            if (s == null) {
                return !c.sup.equals("Ljava/lang/Object;");
            }
            c = s;
        }
        return false;
    }

    private static boolean touchesAtak(final Cls c, final Set<String> seen) {
        if (c == null || !seen.add(c.name)) {
            return false;
        }
        if (atak.classes.get(c.name) == c) {
            return true;
        }
        if (c.sup != null && touchesAtak(resolve(c.sup), seen)) {
            return true;
        }
        for (final String i : c.ifaces) {
            if (touchesAtak(resolve(i), seen)) {
                return true;
            }
        }
        return false;
    }

    /** @return null if the answer depends on a platform class */
    private static Boolean hasMethod(final Cls c, final String method, final Set<String> seen) {
        if (!seen.add(c.name)) {
            return false;
        }
        if (c.virtuals.containsKey(method) || c.directs.contains(method)) {
            return true;
        }
        return inParents(c, parent -> hasMethod(parent, method, seen));
    }

    private static Boolean hasField(final Cls c, final String field, final Set<String> seen) {
        if (!seen.add(c.name)) {
            return false;
        }
        if (c.fields.contains(field)) {
            return true;
        }
        return inParents(c, parent -> hasField(parent, field, seen));
    }

    private interface Lookup {
        Boolean in(Cls parent);
    }

    private static Boolean inParents(final Cls c, final Lookup lookup) {
        boolean unknown = false;
        final List<String> parents = new ArrayList<>(c.ifaces);
        if (c.sup != null) {
            parents.add(c.sup);
        }
        for (final String name : parents) {
            final Cls parent = resolve(name);
            if (parent == null) {
                // every method of Object is known to exist; nothing is inherited from it here
                unknown |= !name.equals("Ljava/lang/Object;");
                continue;
            }
            final Boolean found = lookup.in(parent);
            if (found == null) {
                unknown = true;
            } else if (found) {
                return true;
            }
        }
        return unknown ? null : false;
    }

    // --- reading dex files ---

    private static App load(final String apk) throws Exception {
        final App app = new App();
        try (ZipFile zip = new ZipFile(apk)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes\\d*\\.dex")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    in.transferTo(bytes);
                    readDex(ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN),
                            app);
                }
            }
        }
        return app;
    }

    private static void readDex(final ByteBuffer b, final App app) {
        final int stringCount = b.getInt(0x38), stringOff = b.getInt(0x3C);
        final int typeCount = b.getInt(0x40), typeOff = b.getInt(0x44);
        final int protoCount = b.getInt(0x48), protoOff = b.getInt(0x4C);
        final int fieldCount = b.getInt(0x50), fieldOff = b.getInt(0x54);
        final int methodCount = b.getInt(0x58), methodOff = b.getInt(0x5C);
        final int classCount = b.getInt(0x60), classOff = b.getInt(0x64);

        final String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            final int[] pos = {b.getInt(stringOff + 4 * i)};
            uleb(b, pos); // length in UTF-16 units
            final int start = pos[0];
            int end = start;
            while (b.get(end) != 0) {
                end++;
            }
            final byte[] raw = new byte[end - start];
            b.position(start);
            b.get(raw);
            // names and descriptors are ASCII apart from rare identifiers, which only need to
            // compare equal between the two APKs
            strings[i] = new String(raw, StandardCharsets.ISO_8859_1);
        }
        final String[] types = new String[typeCount];
        for (int i = 0; i < typeCount; i++) {
            types[i] = strings[b.getInt(typeOff + 4 * i)];
        }
        final String[] protos = new String[protoCount];
        for (int i = 0; i < protoCount; i++) {
            final int at = protoOff + 12 * i;
            final StringBuilder sb = new StringBuilder("(");
            final int params = b.getInt(at + 8);
            if (params != 0) {
                final int n = b.getInt(params);
                for (int p = 0; p < n; p++) {
                    sb.append(types[b.getShort(params + 4 + 2 * p) & 0xFFFF]);
                }
            }
            protos[i] = sb.append(')').append(types[b.getInt(at + 4)]).toString();
        }
        final String[] fieldOwner = new String[fieldCount];
        final String[] fieldSig = new String[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            final int at = fieldOff + 8 * i;
            fieldOwner[i] = types[b.getShort(at) & 0xFFFF];
            fieldSig[i] = strings[b.getInt(at + 4)] + ":" + types[b.getShort(at + 2) & 0xFFFF];
            if (!fieldOwner[i].startsWith("[")) {
                app.fieldRefs.add(List.of(fieldOwner[i], fieldSig[i]));
            }
        }
        final String[] methodOwner = new String[methodCount];
        final String[] methodSig = new String[methodCount];
        for (int i = 0; i < methodCount; i++) {
            final int at = methodOff + 8 * i;
            methodOwner[i] = types[b.getShort(at) & 0xFFFF];
            methodSig[i] = strings[b.getInt(at + 4)] + protos[b.getShort(at + 2) & 0xFFFF];
            if (!methodOwner[i].startsWith("[")) {
                app.methodRefs.add(List.of(methodOwner[i], methodSig[i]));
            }
        }
        for (int i = 0; i < classCount; i++) {
            final int at = classOff + 32 * i;
            final Cls c = new Cls();
            c.name = types[b.getInt(at)];
            c.flags = b.getInt(at + 4);
            final int sup = b.getInt(at + 8);
            c.sup = sup == NO_INDEX ? null : types[sup];
            final int ifaces = b.getInt(at + 12);
            if (ifaces != 0) {
                final int n = b.getInt(ifaces);
                for (int p = 0; p < n; p++) {
                    c.ifaces.add(types[b.getShort(ifaces + 4 + 2 * p) & 0xFFFF]);
                }
            }
            final int data = b.getInt(at + 24);
            if (data != 0) {
                final int[] pos = {data};
                final int staticFields = uleb(b, pos), instanceFields = uleb(b, pos);
                final int directs = uleb(b, pos), virtuals = uleb(b, pos);
                for (int pass = 0; pass < 2; pass++) {
                    int index = 0;
                    for (int f = pass == 0 ? staticFields : instanceFields; f > 0; f--) {
                        index += uleb(b, pos);
                        uleb(b, pos);
                        c.fields.add(fieldSig[index]);
                    }
                }
                for (int pass = 0; pass < 2; pass++) {
                    int index = 0;
                    for (int m = pass == 0 ? directs : virtuals; m > 0; m--) {
                        index += uleb(b, pos);
                        final int access = uleb(b, pos);
                        uleb(b, pos);
                        if (pass == 0) {
                            c.directs.add(methodSig[index]);
                        } else {
                            c.virtuals.put(methodSig[index], (access & ACC_ABSTRACT) != 0);
                        }
                    }
                }
            }
            app.classes.putIfAbsent(c.name, c);
        }
    }

    private static int uleb(final ByteBuffer b, final int[] pos) {
        int result = 0;
        int shift = 0;
        int cur;
        do {
            cur = b.get(pos[0]++) & 0xFF;
            result |= (cur & 0x7F) << shift;
            shift += 7;
        } while ((cur & 0x80) != 0);
        return result;
    }
}
