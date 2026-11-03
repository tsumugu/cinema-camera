import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

public final class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<Path> sources;
            try(var paths=Files.walk(Path.of(args[0]))) {
                sources=paths.filter(path -> path.toString().endsWith(".java")).toList();
            }
            JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none"),null,
                files.getJavaFileObjectsFromPaths(sources));
            task.parse();
            for(var d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR)
                throw new AssertionError(d.toString());
            System.out.println("PASS Java syntax parse: "+sources.size()+" application sources (Android API type checking requires SDK)");
        }
    }
}
