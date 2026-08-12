package com.positivity.shared.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;

class CoverageGeneratedTest {

  @Test
  void exposesBytecodeVisibleCoverageMarkerForGeneratedCodeElements() throws ClassNotFoundException {
    Class<?> annotationType = Class.forName("com.positivity.shared.annotation.CoverageGenerated");

    Retention retention = annotationType.getAnnotation(Retention.class);
    Target target = annotationType.getAnnotation(Target.class);

    assertThat(annotationType.isAnnotation()).isTrue();
    assertThat(retention.value()).isEqualTo(RetentionPolicy.CLASS);
    assertThat(target.value())
        .containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR);
  }

  @Test
  void filtersAnnotatedClassCodeFromJacocoAnalysis() throws IOException {
    CoverageBuilder coverage = analyze(TypeLevelGeneratedFixture.class);
    IClassCoverage classCoverage = coverage.getClasses().iterator().next();

    assertThat(classCoverage.getMethods()).isEmpty();
    assertThat(classCoverage.getInstructionCounter().getTotalCount()).isZero();
  }

  @Test
  void filtersOnlyAnnotatedMembersFromJacocoAnalysis() throws IOException {
    CoverageBuilder coverage = analyze(MemberLevelGeneratedFixture.class);
    IClassCoverage classCoverage = coverage.getClasses().iterator().next();

    Set<String> methodNames = methodNames(classCoverage);

    assertEquals(Set.of("handwrittenMethod"), methodNames);
  }

  @Test
  void filtersLombokGeneratedMembersFromJacocoAnalysis() throws IOException {
    CoverageBuilder coverage = analyze(LombokGeneratedFixture.class);
    IClassCoverage classCoverage = coverage.getClasses().iterator().next();

    Set<String> methodNames = methodNames(classCoverage);

    assertEquals(Set.of("<init>", "handwrittenMethod"), methodNames);
  }

  private static CoverageBuilder analyze(Class<?> fixtureType) throws IOException {
    String resourceName = fixtureType.getName().replace('.', '/') + ".class";
    CoverageBuilder coverage = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(new ExecutionDataStore(), coverage);

    try (InputStream classBytes = fixtureType.getClassLoader().getResourceAsStream(resourceName)) {
      assertThat(classBytes).isNotNull();
      analyzer.analyzeClass(classBytes, resourceName);
    }

    return coverage;
  }

  private static Set<String> methodNames(IClassCoverage classCoverage) {
    Set<String> methodNames = new HashSet<>();
    classCoverage.getMethods().forEach(method -> methodNames.add(Objects.requireNonNull(method.getName())));
    return Set.copyOf(methodNames);
  }

  @SuppressWarnings("unused")
  @CoverageGenerated
  private static final class TypeLevelGeneratedFixture {

    String generatedMethod() {
      return "generated";
    }
  }

  @SuppressWarnings("unused")
  private static final class MemberLevelGeneratedFixture {

    @CoverageGenerated
    MemberLevelGeneratedFixture() {
    }

    @CoverageGenerated
    String generatedMethod() {
      return "generated";
    }

    String handwrittenMethod() {
      return "handwritten";
    }
  }

  @SuppressWarnings("unused")
  private static final class LombokGeneratedFixture {

    @Getter
    private final String generatedValue = "generated";

    String handwrittenMethod() {
      return "handwritten";
    }
  }
}
