package com.springcat.polyglot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polyglot Runner — write code in many programming languages and actually run it.
 *
 * Execution is delegated to the public paiza.io runner API (api_key=guest), a no-auth,
 * HTTPS code-execution service that supports a broad set of languages. The app is a thin
 * client:
 *   1. POST /runners/create   -> starts a job, returns an id
 *   2. GET  /runners/get_details?id=... -> polled until status == "completed"
 * It then shows compile output, stdout, stderr and the exit code.
 */
public class MainActivity extends Activity {

    private static final String API_BASE = "https://api.paiza.io";
    private static final String API_KEY = "guest";

    /** A selectable language: what the user sees, and the id the API expects. */
    static class Lang {
        final String label;
        final String id;
        Lang(String label, String id) { this.label = label; this.id = id; }
    }

    private final List<Lang> languages = buildLanguages();
    private final Map<String, String> samples = buildSamples();

    private Spinner languageSpinner;
    private EditText codeInput;
    private EditText stdinInput;
    private TextView codeLabel;
    private TextView stdinLabel;
    private Button runButton;
    private TextView outputView;
    private ProgressBar progress;

    private String currentSample = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        List<String> labels = new ArrayList<>();
        for (Lang l : languages) labels.add(l.label);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);
        languageSpinner.setSelection(0);
        maybeLoadSample(0);
        outputView.setText("Ready. Pick a language and tap Run.");
    }

    // ---------------------------------------------------------------- UI ----

    private View buildUi() {
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Polyglot Runner");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Pick a language, write code, tap Run. Code languages need internet; YARA runs on-device.");
        hint.setPadding(0, dp(2), 0, dp(8));
        root.addView(hint);

        languageSpinner = new Spinner(this);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                maybeLoadSample(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(languageSpinner);

        codeLabel = sectionLabel("Code");
        root.addView(codeLabel);
        codeInput = new EditText(this);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setGravity(Gravity.TOP | Gravity.START);
        codeInput.setMinLines(8);
        codeInput.setHorizontallyScrolling(true);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(codeInput);

        stdinLabel = sectionLabel("Standard input (optional)");
        root.addView(stdinLabel);
        stdinInput = new EditText(this);
        stdinInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        stdinInput.setTypeface(Typeface.MONOSPACE);
        stdinInput.setMinLines(2);
        stdinInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(stdinInput);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runRow.setGravity(Gravity.CENTER_VERTICAL);
        runRow.setPadding(0, dp(8), 0, dp(8));

        runButton = new Button(this);
        runButton.setText("Run  ▶");
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runCode(); }
        });
        runRow.addView(runButton);

        progress = new ProgressBar(this);
        progress.setPadding(dp(16), 0, 0, 0);
        progress.setVisibility(View.GONE);
        runRow.addView(progress);
        root.addView(runRow);

        root.addView(sectionLabel("Output"));
        outputView = new TextView(this);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(dp(10), dp(10), dp(10), dp(10));
        outputView.setBackgroundColor(Color.parseColor("#111418"));
        outputView.setTextColor(Color.parseColor("#E6E6E6"));

        ScrollView outScroll = new ScrollView(this);
        outScroll.addView(outputView);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        outScroll.setLayoutParams(olp);
        root.addView(outScroll);

        ScrollView pageScroll = new ScrollView(this);
        pageScroll.addView(root);
        return pageScroll;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ------------------------------------------------------------- Run ------

    private void runCode() {
        int pos = languageSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= languages.size()) {
            outputView.setText("No language selected yet.");
            return;
        }
        final Lang lang = languages.get(pos);
        final String code = codeInput.getText().toString();
        final String stdin = stdinInput.getText().toString();
        if (code.trim().isEmpty()) {
            outputView.setText(YARA_ID.equals(lang.id)
                    ? "Nothing to scan — write a YARA rule first."
                    : "Nothing to run — the code editor is empty.");
            return;
        }

        final boolean isYara = YARA_ID.equals(lang.id);
        runButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        outputView.setText(isYara ? "Scanning …" : "Running " + lang.label + " …");

        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    if (isYara) {
                        // Runs entirely on-device; the scan target is the input box.
                        result = YaraEngine.scan(code, stdin.getBytes(StandardCharsets.UTF_8));
                    } else {
                        result = execute(lang.id, code, stdin);
                    }
                } catch (Exception e) {
                    result = (isYara ? "Scan failed:\n" : "Request failed:\n") + e
                            + (isYara ? "" : "\n\nCheck your internet connection and try again.");
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        outputView.setText(finalResult);
                        progress.setVisibility(View.GONE);
                        runButton.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /** Full create -> poll -> format round-trip against paiza.io. */
    private String execute(String languageId, String code, String stdin) throws Exception {
        String form = "source_code=" + enc(code)
                + "&language=" + enc(languageId)
                + "&input=" + enc(stdin)
                + "&api_key=" + enc(API_KEY);
        JSONObject created = new JSONObject(httpPostForm(API_BASE + "/runners/create", form));
        if (created.has("error")) {
            return "paiza error: " + created.optString("error");
        }
        String id = created.optString("id", "");
        if (id.isEmpty()) return "Unexpected response:\n" + created;

        JSONObject details = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            String url = API_BASE + "/runners/get_details?id=" + enc(id) + "&api_key=" + enc(API_KEY);
            details = new JSONObject(httpGet(url));
            if (details.has("error")) return "paiza error: " + details.optString("error");
            if ("completed".equals(details.optString("status"))) break;
            Thread.sleep(1000);
        }
        if (details == null || !"completed".equals(details.optString("status"))) {
            return "Timed out waiting for the result. Try again.";
        }
        return formatResult(details);
    }

    private String formatResult(JSONObject d) {
        StringBuilder sb = new StringBuilder();

        String buildErr = optStr(d, "build_stderr");
        String buildCode = optStr(d, "build_exit_code");
        if (!buildErr.trim().isEmpty() || (!buildCode.isEmpty() && !"0".equals(buildCode))) {
            sb.append("— compile —\n");
            if (!buildErr.isEmpty()) {
                sb.append(buildErr);
                if (!buildErr.endsWith("\n")) sb.append("\n");
            }
            sb.append("(build exit ").append(buildCode.isEmpty() ? "?" : buildCode).append(")\n\n");
        }

        String stdout = optStr(d, "stdout");
        String stderr = optStr(d, "stderr");
        boolean any = false;
        if (!stdout.isEmpty()) {
            sb.append(stdout);
            if (!stdout.endsWith("\n")) sb.append("\n");
            any = true;
        }
        if (!stderr.trim().isEmpty()) {
            sb.append("— stderr —\n").append(stderr);
            if (!stderr.endsWith("\n")) sb.append("\n");
            any = true;
        }
        if (!any) sb.append("(no output)\n");

        sb.append("\n(exit ").append(optStr(d, "exit_code")).append(", ")
                .append(optStr(d, "result")).append(", ")
                .append(optStr(d, "time")).append("s)");
        return sb.toString();
    }

    /** paiza returns JSON null for empty fields; normalise those to "". */
    private static String optStr(JSONObject o, String key) {
        if (o.isNull(key)) return "";
        return o.optString(key, "");
    }

    // ------------------------------------------------------------ Samples ---

    private void maybeLoadSample(int pos) {
        if (pos < 0 || pos >= languages.size()) return;
        String id = languages.get(pos).id;
        boolean yara = YARA_ID.equals(id);
        if (codeLabel != null) codeLabel.setText(yara ? "YARA rules" : "Code");
        if (stdinLabel != null) stdinLabel.setText(yara ? "Data to scan (text)" : "Standard input (optional)");
        String sample = samples.get(id);
        if (sample == null) sample = "";
        String cur = codeInput.getText().toString();
        // Only overwrite when the editor is empty or still holds a prior sample,
        // so we never clobber code the user actually typed.
        if (cur.trim().isEmpty() || cur.equals(currentSample)) {
            codeInput.setText(sample);
            currentSample = sample;
        }
    }

    private static final String YARA_ID = "yara";

    private static List<Lang> buildLanguages() {
        List<Lang> l = new ArrayList<>();
        l.add(new Lang("YARA (on-device scan)", YARA_ID));
        l.add(new Lang("Python 3", "python3"));
        l.add(new Lang("JavaScript (Node)", "javascript"));
        l.add(new Lang("TypeScript", "typescript"));
        l.add(new Lang("Java", "java"));
        l.add(new Lang("C", "c"));
        l.add(new Lang("C++", "cpp"));
        l.add(new Lang("C#", "csharp"));
        l.add(new Lang("Go", "go"));
        l.add(new Lang("Rust", "rust"));
        l.add(new Lang("Ruby", "ruby"));
        l.add(new Lang("PHP", "php"));
        l.add(new Lang("Kotlin", "kotlin"));
        l.add(new Lang("Swift", "swift"));
        l.add(new Lang("Scala", "scala"));
        l.add(new Lang("Perl", "perl"));
        l.add(new Lang("Haskell", "haskell"));
        l.add(new Lang("Objective-C", "objective-c"));
        l.add(new Lang("Elixir", "elixir"));
        l.add(new Lang("Erlang", "erlang"));
        l.add(new Lang("Clojure", "clojure"));
        l.add(new Lang("F#", "fsharp"));
        l.add(new Lang("Visual Basic", "vb"));
        l.add(new Lang("D", "d"));
        l.add(new Lang("Bash", "bash"));
        l.add(new Lang("R", "r"));
        l.add(new Lang("Scheme", "scheme"));
        l.add(new Lang("Common Lisp", "commonlisp"));
        l.add(new Lang("COBOL", "cobol"));
        l.add(new Lang("Python 2", "python"));
        l.add(new Lang("CoffeeScript", "coffeescript"));
        return l;
    }

    private static Map<String, String> buildSamples() {
        Map<String, String> m = new HashMap<>();
        m.put(YARA_ID,
                "rule SuspiciousText {\n"
                + "    meta:\n"
                + "        description = \"Demo rule — edit me\"\n"
                + "    strings:\n"
                + "        $a = \"malware\" nocase\n"
                + "        $b = \"password\" fullword\n"
                + "        $mz = { 4D 5A }          // PE/EXE header\n"
                + "        $ip = /\\d{1,3}(\\.\\d{1,3}){3}/  // an IPv4-ish string\n"
                + "    condition:\n"
                + "        2 of them or filesize < 20\n"
                + "}\n");
        m.put("python3", "print(\"Hello from Python!\")\n");
        m.put("python", "print \"Hello from Python 2!\"\n");
        m.put("javascript", "console.log(\"Hello from JavaScript!\");\n");
        m.put("typescript", "const msg: string = \"Hello from TypeScript!\";\nconsole.log(msg);\n");
        m.put("java", "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from Java!\");\n    }\n}\n");
        m.put("c", "#include <stdio.h>\nint main(void) {\n    printf(\"Hello from C!\\n\");\n    return 0;\n}\n");
        m.put("cpp", "#include <iostream>\nint main() {\n    std::cout << \"Hello from C++!\" << std::endl;\n}\n");
        m.put("csharp", "using System;\nclass Program {\n    static void Main() {\n        Console.WriteLine(\"Hello from C#!\");\n    }\n}\n");
        m.put("go", "package main\nimport \"fmt\"\nfunc main() {\n    fmt.Println(\"Hello from Go!\")\n}\n");
        m.put("rust", "fn main() {\n    println!(\"Hello from Rust!\");\n}\n");
        m.put("ruby", "puts \"Hello from Ruby!\"\n");
        m.put("php", "<?php\necho \"Hello from PHP!\\n\";\n");
        m.put("kotlin", "fun main() {\n    println(\"Hello from Kotlin!\")\n}\n");
        m.put("swift", "print(\"Hello from Swift!\")\n");
        m.put("scala", "object Main extends App {\n    println(\"Hello from Scala!\")\n}\n");
        m.put("perl", "print \"Hello from Perl!\\n\";\n");
        m.put("haskell", "main :: IO ()\nmain = putStrLn \"Hello from Haskell!\"\n");
        m.put("bash", "echo \"Hello from Bash!\"\n");
        m.put("r", "cat(\"Hello from R!\\n\")\n");
        return m;
    }

    // -------------------------------------------------------- HTTP helpers --

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        try {
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    private static String httpPostForm(String urlStr, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        byte[] payload = form.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        try {
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
        if (in == null) return "{\"error\":\"HTTP " + code + " with empty body\"}";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
