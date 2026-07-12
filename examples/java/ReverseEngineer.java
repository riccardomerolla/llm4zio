//> using dep "io.github.riccardomerolla:llm4zio-java:3.13.1"
//> using scala "3.8.3"
//> using jvm 21

// Read-only reverse-engineering, authored in Java — the Java-surface counterpart of reverse-engineer.sc (condensed:
// the same discover → architecture → domain → ADRs phases; the .sc adds a reverse-spec + review pass).
//
// Documents an existing repo: an architecture overview and a domain-model doc written by the (read-only) reasoning
// connector, then the significant architecture decisions inferred as structured ADRs under docs/adr/. Each phase is
// committed as it lands; already-written docs are left alone, so a re-run resumes.
//
// Seed a starter:  examples/seed.sh reverse-engineer --java
// Run:             scala-cli run ReverseEngineer.java -- "Document this repository for a new contributor."

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import llm4zio.javaapi.*;

public class ReverseEngineer {
  static final String ARCHITECTURE_INSTRUCTIONS = """
      You are documenting an existing repository for a new contributor. Explore it and write an
      architecture overview: the modules, how they depend on each other, the entry points, and
      the build/test commands. Plain Markdown, start with a # heading, no preamble.""";

  static final String DOMAIN_INSTRUCTIONS = """
      You are documenting an existing repository. Explore it and write the domain model: the core
      concepts/types, their relationships and invariants, and the language the code uses for them.
      Plain Markdown, start with a # heading, no preamble.""";

  public static void main(String[] args) {
    Llm4zioJava.flow(args, "Document this repository for a new contributor.", flow -> {
      flow.stage("Branch", () -> flow.git().checkoutOrCreate("docs/reverse-engineer"));

      var architecture = proseDoc(flow, "Architecture", flow.workDir().resolve("docs/architecture.md"),
          flow.userPrompt(), ARCHITECTURE_INSTRUCTIONS);
      var domain = proseDoc(flow, "Domain model", flow.workDir().resolve("docs/domain-model.md"),
          flow.userPrompt(), DOMAIN_INSTRUCTIONS);

      flow.stage("ADRs", () -> {
        var adrDir = flow.workDir().resolve("docs/adr");
        if (Files.exists(adrDir)) {
          flow.info("docs/adr exists — leaving it alone");
          return;
        }
        var adrs = flow.adrs("Infer the significant architecture decisions from these documents, as ADRs."
            + "\n\nArchitecture:\n" + architecture + "\n\nDomain model:\n" + domain);
        for (var adr : adrs) {
          writeIfAbsent(adrDir.resolve(String.format("%04d-%s.md", adr.number(), slugify(adr.title()))),
              Adrs.render(adr));
        }
        flow.git().commitAll("docs: adrs");
      });
    });
  }

  /** Write one prose doc if absent (resume-safe), commit it, and return its content either way. */
  static String proseDoc(JavaFlow flow, String stageName, Path path, String prompt, String instructions) {
    return flow.stage(stageName, () -> {
      try {
        if (Files.exists(path)) {
          return Files.readString(path);
        }
        var content = flow.brief(prompt, instructions);
        writeIfAbsent(path, content);
        flow.git().commitAll("docs: " + path.getFileName());
        return content;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  static void writeIfAbsent(Path path, String content) {
    try {
      if (!Files.exists(path)) {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String slugify(String title) {
    return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }
}
