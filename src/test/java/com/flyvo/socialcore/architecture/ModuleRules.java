package com.flyvo.socialcore.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;

final class ModuleRules {
  static final Map<String, Set<String>> DEPENDENCIES =
      Map.ofEntries(
          Map.entry("account", Set.of()),
          Map.entry("identity", Set.of("account", "profile", "community")),
          Map.entry("profile", Set.of("account", "graph", "media")),
          Map.entry("community", Set.of("account")),
          Map.entry("graph", Set.of("account")),
          Map.entry("media", Set.of("account")),
          Map.entry("content", Set.of("account", "graph", "community", "media", "profile")),
          Map.entry("feed", Set.of("content", "graph", "community", "profile")),
          Map.entry("notification", Set.of("content", "community", "graph", "profile", "account")),
          Map.entry("search", Set.of("content", "community", "profile", "account")),
          Map.entry("moderation", Set.of("content", "community", "account", "profile")),
          Map.entry("platform", Set.of()));

  private ModuleRules() {}

  static ArchRule boundaries(String root) {
    return classes()
        .that()
        .resideInAPackage(root + "..")
        .should(
            new ArchCondition<>("respect the module dependency allowlist and API-only boundaries") {
              @Override
              public void check(JavaClass source, ConditionEvents events) {
                String from = module(source, root);
                if (from == null) return; // Only the bootstrap class lives at the root.
                if (!DEPENDENCIES.containsKey(from)) {
                  events.add(
                      SimpleConditionEvent.violated(source, "Unknown module: " + source.getName()));
                  return;
                }
                for (var dependency : source.getDirectDependenciesFromSelf()) {
                  JavaClass target = dependency.getTargetClass();
                  String to = module(target, root);
                  if (to == null || from.equals(to)) continue;
                  boolean allowed =
                      (to.equals("platform") || DEPENDENCIES.get(from).contains(to))
                          && target
                              .getPackageName()
                              .matches(
                                  java.util.regex.Pattern.quote(root + "." + to + ".api")
                                      + "(?:\\..*)?");
                  if (!allowed)
                    events.add(
                        SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                }
              }
            });
  }

  private static String module(JavaClass type, String root) {
    String pkg = type.getPackageName();
    if (!pkg.startsWith(root + ".")) return null;
    return pkg.substring(root.length() + 1).split("\\.")[0];
  }
}
