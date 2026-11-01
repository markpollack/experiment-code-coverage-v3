package io.tuvium.experiment.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.experiment.runtime.Evidence;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Domain evidence acquisition; failures never become a zero-percent measurement. */
public final class CoverageEvidence {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public record Build(int exitCode, boolean timedOut, boolean drained, long elapsedMs, FileRef log) {
        public boolean passed() { return !timedOut && drained && exitCode == 0; }
    }
    public record Coverage(double line, double branch, int covered, int missed, FileRef report) {}

    static void write(Path path, Object value) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }

    static Build build(Path workspace, Path evidence, String name, long budgetMs, String... goals) throws Exception {
        if (budgetMs <= 0) throw new IllegalStateException("No remaining build budget");
        Files.createDirectories(evidence);
        Path log = evidence.resolve(name + ".log");
        if (Files.exists(log)) throw new IOException("Refusing build evidence overwrite");
        var command = new ArrayList<String>();
        command.add(workspace.resolve("mvnw").toString());
        command.add("-B"); command.add("-ntp");
        command.add("-Dmaven.repo.local=" + System.getProperty("coverage.mavenRepo"));
        command.add("-Dspring-javaformat.skip=true"); command.addAll(List.of(goals));
        write(evidence.resolve(name + "-command.json"), Map.of("argv",command,"budgetMs",budgetMs,"workspace",workspace.toString()));
        long start=System.nanoTime();
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done=process.waitFor(budgetMs,TimeUnit.MILLISECONDS);
        if (!done) {
            var children=process.descendants().toList();
            children.forEach(ProcessHandle::destroy); process.destroy();
            process.waitFor(2000,TimeUnit.MILLISECONDS);
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(2000,TimeUnit.MILLISECONDS);
            if (children.stream().anyMatch(ProcessHandle::isAlive)) throw new IllegalStateException("Build descendants remain active; workspace quarantined");
        }
        var result = new Build(process.isAlive() ? -1 : process.exitValue(), !done, !process.isAlive(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start), Evidence.reference(log,"maven-log"));
        write(evidence.resolve(name + "-result.json"),result);
        if (!result.drained()) throw new IllegalStateException("Build still active; workspace quarantined");
        return result;
    }

    /** Reads only report-level counters, not the last nested counter encountered. */
    public static Coverage read(Path xml, Path saved) throws Exception {
        if (!Files.isRegularFile(xml)) throw new IOException("Missing fresh JaCoCo report: " + xml);
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var root=factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
        if (!root.getTagName().equals("report")) throw new IOException("Not a JaCoCo report");
        int[] line=null, branch=null;
        for (var n=root.getFirstChild(); n!=null; n=n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("counter")) {
                int covered=Integer.parseInt(e.getAttribute("covered")), missed=Integer.parseInt(e.getAttribute("missed"));
                if (covered<0 || missed<0) throw new IOException("Negative coverage counter");
                if (e.getAttribute("type").equals("LINE")) { if(line!=null) throw new IOException("Duplicate LINE counter"); line=new int[]{covered,missed}; }
                if (e.getAttribute("type").equals("BRANCH")) branch=new int[]{covered,missed};
            }
        }
        if(line==null || (long)line[0]+line[1]<=0) throw new IOException("Missing/empty LINE denominator");
        if(branch==null || (long)branch[0]+branch[1]<=0) throw new IOException("Missing/empty PetClinic BRANCH denominator");
        Files.copy(xml,saved);
        return new Coverage(100.0*line[0]/((long)line[0]+line[1]),100.0*branch[0]/((long)branch[0]+branch[1]),line[0],line[1],Evidence.reference(saved,"jacoco-report"));
    }
    static List<Measurement> metrics(Coverage c,String scope) {
        return List.of(Measurement.measured("lineCoverage","percent",c.line(),c.report().path(),scope),
                Measurement.measured("branchCoverage","percent",c.branch(),c.report().path(),scope),
                Measurement.measured("linesCovered","line",c.covered(),c.report().path(),scope),
                Measurement.measured("linesTotal","line",(long)c.covered()+c.missed(),c.report().path(),scope));
    }
    static double value(List<Measurement> values,String name) {
        return values.stream().filter(m->m.name().equals(name)&&m.state().equals("MEASURED")).findFirst().orElseThrow(()->new IllegalArgumentException("Unavailable " + name)).values().getFirst();
    }
    static long remaining(Instant deadline,long capMs) { return Math.min(capMs,Duration.between(Instant.now(),deadline).toMillis()); }
}
