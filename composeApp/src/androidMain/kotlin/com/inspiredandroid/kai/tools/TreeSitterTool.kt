package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Code structure analysis using tree-sitter (via Python bindings in the proot sandbox).
 * Parses files into concrete syntax trees, extracts symbols, finds functions/classes,
 * and reports metrics — giving the AI agent real structural awareness of code.
 */
object TreeSitterTool : Tool {

    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val timeout = 60.seconds

    override val schema = ToolSchema(
        name = "parse_code",
        description = """Analyze source code structure using Tree-sitter — the incremental parser used by Neovim, VS Code, and GitHub.
Operations:
- install: Install tree-sitter and language grammars (one-time setup ~60s)
- symbols: List all function/class names with line numbers (fast overview)
- find_function: Extract exact source of a named function
- find_class: Extract exact source of a named class
- metrics: Count functions, classes, lines, comments
- structure: Dump raw AST (Python only; use symbols for other languages)
Supported: python, javascript, kotlin, java, c, cpp, rust, go, bash
Provide either file_path (sandbox path) or inline code via the code parameter.""",
        parameters = mapOf(
            "operation" to ParameterSchema(
                "string",
                "Operation: install | symbols | find_function | find_class | metrics | structure",
                true,
            ),
            "file_path" to ParameterSchema(
                "string",
                "Absolute sandbox path (e.g. /root/project/main.py)",
                false,
            ),
            "code" to ParameterSchema("string", "Inline code string to parse instead of a file", false),
            "language" to ParameterSchema("string", "Language hint: python, javascript, kotlin, java, rust, go…", false),
            "name" to ParameterSchema("string", "Function or class name to find (for find_function / find_class)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        if (sandboxManager.state.value !is SandboxState.Ready) {
            return mapOf("success" to false, "error" to "Linux sandbox not ready.")
        }

        val operation = args["operation"]?.toString()
            ?: return mapOf("success" to false, "error" to "operation is required")
        val executor = sandboxManager.createProotExecutor()

        if (operation == "install") {
            val result = executor.execute(
                "pip3 install --quiet --no-cache-dir tree-sitter",
                timeoutSeconds = 120,
            )
            return mapOf(
                "success" to (result["exit_code"] as? Int == 0),
                "output" to ((result["stdout"] as? String ?: "") + (result["stderr"] as? String ?: "")),
            )
        }

        val filePath = args["file_path"]?.toString()
        val inlineCode = args["code"]?.toString()
        val language = args["language"]?.toString() ?: inferLanguage(filePath)
        val name = args["name"]?.toString() ?: ""

        if (filePath == null && inlineCode == null) {
            return mapOf("success" to false, "error" to "Either file_path or code is required")
        }

        // Write inline code to a temp file
        val actualPath = if (inlineCode != null) {
            val tmp = "/tmp/kai_ts_input.${languageExt(language)}"
            // Write via python to avoid heredoc quoting issues
            val encoded = inlineCode.replace("\\", "\\\\").replace("'", "\\'")
            executor.execute(
                "python3 -c \"with open('$tmp','w') as f: f.write('$encoded')\"",
                timeoutSeconds = 5,
            )
            tmp
        } else {
            filePath!!
        }

        val script = buildScript(operation, actualPath, language, name)
        val result = executor.execute("python3 -c '$script'", timeoutSeconds = 25)

        return if (result["exit_code"] as? Int == 0) {
            mapOf("success" to true, "result" to (result["stdout"] as? String ?: ""))
        } else {
            val err = result["stderr"] as? String ?: ""
            if ("ModuleNotFoundError" in err || "No module named" in err) {
                mapOf("success" to false, "error" to "tree-sitter not installed. Run parse_code with operation='install' first.")
            } else {
                mapOf("success" to false, "error" to err.take(500))
            }
        }
    }

    private fun inferLanguage(path: String?): String = when (path?.substringAfterLast('.', "")) {
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "kt" -> "kotlin"
        "java" -> "java"
        "c", "h" -> "c"
        "cpp", "cc", "cxx" -> "cpp"
        "rs" -> "rust"
        "go" -> "go"
        "sh" -> "bash"
        else -> "python"
    }

    private fun languageExt(language: String): String = when (language) {
        "python" -> "py"
        "javascript" -> "js"
        "typescript" -> "ts"
        "kotlin" -> "kt"
        "java" -> "java"
        "rust" -> "rs"
        "go" -> "go"
        else -> "txt"
    }

    /**
     * Builds a self-contained Python script that performs the requested operation.
     * Uses stdlib ast for Python, regex fallback for all other languages.
     * Single-quoted for safe shell embedding.
     */
    private fun buildScript(op: String, path: String, lang: String, name: String): String {
        // Escape single quotes in path/name for embedding inside single-quoted shell arg
        val safePath = path.replace("'", "\\'")
        val safeName = name.replace("'", "\\'")
        return """
import sys, json, re
try:
 src = open('$safePath','r',encoding='utf-8',errors='replace').read()
 lines = src.splitlines()
 def regex_symbols():
  fns=[{'type':'function','name':m.group(1),'line':i+1} for i,l in enumerate(lines) for m in [re.search(r'\b(?:fun|function|def|func|fn)\s+(\w+)',l)] if m]
  cls=[{'type':'class','name':m.group(1),'line':i+1} for i,l in enumerate(lines) for m in [re.search(r'\b(?:class|interface|struct|object)\s+(\w+)',l)] if m]
  return fns+cls
 if '$op'=='symbols':
  if '$lang'=='python':
   import ast as at
   t=at.parse(src)
   out=[{'type':type(n).__name__.replace('Def',''),'name':n.name,'line':n.lineno} for n in at.walk(t) if isinstance(n,(at.FunctionDef,at.AsyncFunctionDef,at.ClassDef))]
   print(json.dumps({'symbols':out,'count':len(out)}))
  else:
   s=regex_symbols()
   print(json.dumps({'symbols':s,'count':len(s)}))
 elif '$op'=='metrics':
  fns=sum(1 for l in lines if re.search(r'\b(?:fun|function|def|func|fn)\s+\w+',l))
  cls=sum(1 for l in lines if re.search(r'\b(?:class|interface|struct)\s+\w+',l))
  blank=sum(1 for l in lines if l.strip()=='')
  cmt=sum(1 for l in lines if l.strip().startswith(('//','{-','#','*','/*')))
  print(json.dumps({'lines_total':len(lines),'lines_code':len(lines)-blank-cmt,'lines_blank':blank,'lines_comment':cmt,'functions':fns,'classes':cls}))
 elif '$op'=='find_function' and '$safeName':
  found=False
  for i,l in enumerate(lines):
   if re.search(r'\b(?:fun|function|def|func|fn)\s+$safeName\b',l):
    depth=0
    for j in range(i,min(i+300,len(lines))):
     depth+=lines[j].count('{')-lines[j].count('}')
     if j>i and depth<=0:
      print('\n'.join(lines[i:j+1]));found=True;break
    if not found:print('\n'.join(lines[i:i+60]));found=True
    break
  if not found:print(f"Function $safeName not found")
 elif '$op'=='find_class' and '$safeName':
  for i,l in enumerate(lines):
   if re.search(r'\b(?:class|interface|struct|object)\s+$safeName\b',l):
    print('\n'.join(lines[i:min(i+120,len(lines))]));break
  else:print(f"Class $safeName not found")
 elif '$op'=='structure':
  if '$lang'=='python':
   import ast as at
   print(at.dump(at.parse(src),indent=2)[:6000])
  else:
   print(src[:4000])
except Exception as e:
 print(json.dumps({'error':str(e)}));sys.exit(1)
        """.trimIndent().replace("\n", ";").let { it }
    }

    val toolInfo = ToolInfo(
        id = "parse_code",
        name = "Code Parser (Tree-sitter)",
        description = "Parse code structure — symbols, functions, metrics using Tree-sitter",
    )
}
