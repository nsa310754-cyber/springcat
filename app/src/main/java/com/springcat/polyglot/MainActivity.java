package com.springcat.polyglot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
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
import java.io.ByteArrayOutputStream;
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
    private TextView outputLabel;
    private TextView outputView;
    private ScrollView outScroll;
    private WebView previewWeb;
    private ProgressBar progress;
    private WebView jsWebView;

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
        hint.setText("Pick a language, write code, tap Run.\nOffline: JavaScript (offline) & YARA. Others need internet.");
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

        Button uploadButton = new Button(this);
        uploadButton.setText("📁 File");
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        runRow.addView(uploadButton, equalWeight());

        runButton = new Button(this);
        runButton.setText("Run  ▶");
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runCode(); }
        });
        runRow.addView(runButton, equalWeight());

        Button clearButton = new Button(this);
        clearButton.setText("Clear");
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearCode(); }
        });
        runRow.addView(clearButton, equalWeight());

        progress = new ProgressBar(this);
        progress.setPadding(dp(16), 0, 0, 0);
        progress.setVisibility(View.GONE);
        runRow.addView(progress);
        root.addView(runRow);

        outputLabel = sectionLabel("Output");
        root.addView(outputLabel);
        outputView = new TextView(this);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(dp(10), dp(10), dp(10), dp(10));
        outputView.setBackgroundColor(Color.parseColor("#111418"));
        outputView.setTextColor(Color.parseColor("#E6E6E6"));

        outScroll = new ScrollView(this);
        outScroll.addView(outputView);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        outScroll.setLayoutParams(olp);
        root.addView(outScroll);

        // Rendered preview for HTML / JSX (hidden until used).
        previewWeb = new WebView(this);
        previewWeb.getSettings().setJavaScriptEnabled(true);
        previewWeb.getSettings().setAllowFileAccess(true);
        previewWeb.getSettings().setDomStorageEnabled(true);
        previewWeb.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
        previewWeb.setLayoutParams(plp);
        previewWeb.setVisibility(View.GONE);
        root.addView(previewWeb);

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

    private LinearLayout.LayoutParams equalWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    // --------------------------------------------------- File upload / clear -

    private static final int REQUEST_PICK_FILE = 42;
    private static final int MAX_UPLOAD_CHARS = 1_000_000;

    /** Open the system file picker (SAF — no storage permission needed). */
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_PICK_FILE);
        } catch (Exception e) {
            outputView.setText("No file picker available on this device.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String content = readUri(uri);
            boolean truncated = content.length() > MAX_UPLOAD_CHARS;
            if (truncated) content = content.substring(0, MAX_UPLOAD_CHARS);
            // Load into the box that makes sense for the current language.
            boolean yara = isYaraSelected();
            EditText target = yara ? stdinInput : codeInput;
            target.setText(content);
            if (!yara) currentSample = ""; // so switching language won't overwrite it
            String name = queryName(uri);
            outputView.setText("Loaded " + (name == null ? "file" : name)
                    + " → " + (yara ? "scan data" : "code")
                    + " (" + content.length() + " chars"
                    + (truncated ? ", truncated" : "") + ").");
        } catch (Exception e) {
            outputView.setText("Could not read the file:\n" + e);
        }
    }

    private boolean isYaraSelected() {
        int pos = languageSpinner.getSelectedItemPosition();
        return pos >= 0 && pos < languages.size() && YARA_ID.equals(languages.get(pos).id);
    }

    private String readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot open stream");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int r;
            while ((r = in.read(chunk)) != -1) {
                buf.write(chunk, 0, r);
                if (buf.size() > MAX_UPLOAD_CHARS * 2L) break;
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Clear the code editor and reset the output/preview. */
    private void clearCode() {
        codeInput.setText("");
        currentSample = "";
        if (previewWeb != null) previewWeb.loadUrl("about:blank");
        outputView.setText("Cleared.");
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

        // HTML / JSX render into the preview WebView, on the UI thread.
        if (HTML_ID.equals(lang.id)) { renderHtml(code); return; }
        if (JSX_ID.equals(lang.id)) { renderJsx(code); return; }

        // JavaScript (offline) runs in the system WebView, on the UI thread.
        if (JS_OFFLINE_ID.equals(lang.id)) {
            runJavascriptOffline(code);
            return;
        }

        // Remote languages need the network; fail fast with a helpful message.
        if (!isOfflineLang(lang.id) && !isOnline()) {
            outputView.setText("You appear to be offline, and " + lang.label
                    + " runs on a remote server.\n\n"
                    + "Works with no internet:\n"
                    + "  • HTML (offline preview)\n"
                    + "  • JSX / React (offline)\n"
                    + "  • JavaScript (offline)\n"
                    + "  • YARA (on-device scan)");
            return;
        }

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

    // ------------------------------------------------------ HTML / JSX view -

    /** Render raw HTML into the preview WebView (offline). */
    private void renderHtml(String html) {
        outputLabel.setText("Preview");
        outScroll.setVisibility(View.GONE);
        previewWeb.setVisibility(View.VISIBLE);
        previewWeb.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /**
     * Render JSX/React (offline). React, ReactDOM and Babel-standalone ship in
     * assets/; the document is loaded with a file:///android_asset/ base URL so
     * those local scripts resolve, and Babel auto-transpiles the text/babel block.
     */
    private void renderJsx(String jsx) {
        outputLabel.setText("Preview");
        outScroll.setVisibility(View.GONE);
        previewWeb.setVisibility(View.VISIBLE);

        String doc = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{font-family:sans-serif;margin:12px;color:#111}</style>"
                + "<script>window.onerror=function(m,s,l,c,e){"
                + "document.body.insertAdjacentHTML('beforeend',"
                + "'<pre style=\"color:#c00;white-space:pre-wrap\">'+((e&&e.stack)?e.stack:m)+'</pre>');"
                + "return true;};</script>"
                + "</head><body>"
                + "<div id=\"root\"></div>"
                + "<script src=\"react.production.min.js\"></script>"
                + "<script src=\"react-dom.production.min.js\"></script>"
                + "<script src=\"babel.min.js\"></script>"
                + "<script type=\"text/babel\" data-presets=\"react\">\n"
                + jsx
                + "\n</script></body></html>";
        previewWeb.loadDataWithBaseURL("file:///android_asset/", doc, "text/html", "utf-8", null);
    }

    // ------------------------------------------------ JavaScript (offline) --

    /** Evaluate JS in the system WebView (no network). Async via a callback. */
    private void runJavascriptOffline(String code) {
        runButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        outputView.setText("Running JavaScript (offline) …");

        if (jsWebView == null) {
            jsWebView = new WebView(this);
            jsWebView.getSettings().setJavaScriptEnabled(true);
        }
        String script = buildJsHarness(code);
        jsWebView.evaluateJavascript(script, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String text = decodeJsResult(value);
                if (text.trim().isEmpty()) text = "(no output)";
                outputView.setText(text);
                progress.setVisibility(View.GONE);
                runButton.setEnabled(true);
            }
        });
    }

    /**
     * Wrap the user's code so console.* output is captured and both runtime and
     * syntax errors are reported. The code is passed to eval() as a JSON-quoted
     * string so a parse error is catchable rather than breaking the whole script.
     */
    private static String buildJsHarness(String code) {
        return "(function(){"
                + "var __out='';"
                + "function __fmt(a){try{return (typeof a==='object'&&a!==null)?JSON.stringify(a):String(a);}catch(e){return String(a);}}"
                + "var __log=function(){var p=[];for(var i=0;i<arguments.length;i++)p.push(__fmt(arguments[i]));__out+=p.join(' ')+'\\n';};"
                + "console.log=console.info=console.warn=console.error=console.debug=__log;"
                + "try{eval(" + JSONObject.quote(code) + ");}"
                + "catch(e){__out+='Error: '+((e&&e.name)?(e.name+': '+e.message):String(e))+'\\n';}"
                + "return __out;})()";
    }

    /** evaluateJavascript hands back a JSON-encoded value; turn it into text. */
    private static String decodeJsResult(String value) {
        if (value == null || value.equals("null")) return "";
        try {
            Object o = new org.json.JSONTokener(value).nextValue();
            return o == null ? "" : o.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true; // can't tell — let the request try.
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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
        boolean jsOffline = JS_OFFLINE_ID.equals(id);
        boolean html = HTML_ID.equals(id);
        boolean jsx = JSX_ID.equals(id);
        if (codeLabel != null) {
            String cl = "Code";
            if (yara) cl = "YARA rules";
            else if (html) cl = "HTML";
            else if (jsx) cl = "JSX (React) code";
            codeLabel.setText(cl);
        }
        if (stdinLabel != null) {
            String sl = "Standard input (optional)";
            if (yara) sl = "Data to scan (text)";
            else if (jsOffline || html || jsx) sl = "Standard input (not used here)";
            stdinLabel.setText(sl);
        }
        // Show the right output surface for this language.
        boolean preview = isPreviewLang(id);
        if (outputLabel != null) outputLabel.setText(preview ? "Preview" : "Output");
        if (previewWeb != null && outScroll != null) {
            previewWeb.setVisibility(preview ? View.VISIBLE : View.GONE);
            outScroll.setVisibility(preview ? View.GONE : View.VISIBLE);
        }
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
    private static final String JS_OFFLINE_ID = "js-offline";
    private static final String HTML_ID = "html";
    private static final String JSX_ID = "jsx";

    /** Languages that run fully on the device, with no network. */
    private static boolean isOfflineLang(String id) {
        return YARA_ID.equals(id) || JS_OFFLINE_ID.equals(id)
                || HTML_ID.equals(id) || JSX_ID.equals(id);
    }

    /** Languages rendered visually in the preview WebView. */
    private static boolean isPreviewLang(String id) {
        return HTML_ID.equals(id) || JSX_ID.equals(id);
    }

    private static List<Lang> buildLanguages() {
        List<Lang> l = new ArrayList<>();
        l.add(new Lang("HTML (offline preview)", HTML_ID));
        l.add(new Lang("JSX / React (offline)", JSX_ID));
        l.add(new Lang("JavaScript (offline)", JS_OFFLINE_ID));
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
        m.put(HTML_ID,
                "<!doctype html>\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<h1>Hello, HTML 👋</h1>\n"
                + "<p>Edit this and tap <b>Run</b> to preview it.</p>\n"
                + "<button onclick=\"this.textContent='clicked!'\">Click me</button>\n");
        m.put(JSX_ID,
                "function App() {\n"
                + "  const [n, setN] = React.useState(0);\n"
                + "  return (\n"
                + "    <div style={{fontFamily:'sans-serif'}}>\n"
                + "      <h2>Hello from JSX ⚛️</h2>\n"
                + "      <button onClick={() => setN(n + 1)}>Clicked {n} times</button>\n"
                + "    </div>\n"
                + "  );\n"
                + "}\n"
                + "ReactDOM.createRoot(document.getElementById('root')).render(<App />);\n");
        m.put(JS_OFFLINE_ID,
                "// Runs on-device in the system WebView — no internet needed.\n"
                + "// Note: browser-style JS (no Node APIs like require/process).\n"
                + "console.log(\"Hello from offline JavaScript!\");\n"
                + "const sq = n => n * n;\n"
                + "for (let i = 1; i <= 3; i++) console.log(i, \"squared =\", sq(i));\n");
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
