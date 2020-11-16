import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@Slf4j
@AnalyzeClasses(
        packages = {"archunit"},
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeArchives.class,
                ImportOption.DoNotIncludeJars.class,
        })
public class ArchunitTest {

    public DescribedPredicate<JavaClass> annotatedWithSlf4j =
            new DescribedPredicate<JavaClass>("have a field of log") {
                @Override
                public boolean apply(JavaClass input) {
                    try {
                        input.getField("log");
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                    return true;
                }
            };

   // 抛出异常时不能只打印e.getMessage()
   // Ex: log.error(e.getMessage());
   @ArchTest
   public void logStandard2(JavaClasses classes) {
       classes()
               .that(annotatedWithSlf4j)
               .should(notOnlyContainsGetMessage2)
               .because("it should contain more message")
               .check(classes);
   }

    public ArchCondition<JavaClass> ontOnlyContainsGetMessage =
            new ArchCondition<JavaClass>("not only pass getMessage as parameter") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    File file = new File("src/main/java/" + item.getPackageName() + "/" + item.getSimpleName() + ".java");
                    checkLineContentForParams(item, events, file);
                }
            };

    public ArchCondition<JavaClass> notOnlyContainsGetMessage2 =
            new ArchCondition<JavaClass>("not only pass getMessage as parameter") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getCallsFromSelf()
                            .stream()
                            .map(x -> x.getTarget().getFullName())
                            .forEach(x -> {
                                if (x.contains("getMessage()")) {
                                    //File file = new File("src/main/java/" + item.getPackageName() + "/" + item.getSimpleName() + ".java");
                                    File file = new File("src/main/java/" + item.getPackageName().replaceAll("\\.", "/") + "/" + item.getSimpleName() + ".java");
                                    checkLineContentForParams(item, events, file);
                                }
                            });
                }
            };

    public void checkLineContentForParams(JavaClass item, ConditionEvents events, File file) {
        try {
            Scanner scanner = new Scanner(file);
            String logPattern = "log.error\\([A-Za-z0-9]+\\.getMessage\\(\\)\\);";
            Pattern pattern = Pattern.compile(logPattern);
            int lineNumber = 0;

            while (scanner.hasNext()) {
                lineNumber++;
                String line = scanner.nextLine();
                Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {
                    events.add(SimpleConditionEvent.violated(item,
                            "only pass getMessage as parameter is not allowed at line " + lineNumber
                                    + " in " + item.getSimpleName() + "" + ".java"));
                }
            }
        }
        catch (Exception e) {
            System.out.println("cannot find file");
        }
    }

    // 禁止输出除 info,warn,error 之外的日志级别
    @ArchTest
    public void logStandard3(JavaClasses classes) {
        classes()
                .that(annotatedWithSlf4j)
                .should(onlyCallCertainLogLevel)
                .because("it should contains more message")
                .check(classes);
    }

    public ArchCondition<JavaClass> onlyCallCertainLogLevel =
            new ArchCondition<JavaClass>("log level should be info, warn, error") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {

                }
            };

    // 禁止又抛出异常又打印日志
    // Ex: catch (Exception e) {
    //       log.error("xxx", e);
    //       throw new xxxException();
    //     }
    @ArchTest
    public void logStandard4(JavaClasses classes) {
        classes()
                .that(hasMethodThrowException)
                .should(notPrintLogAndThrowException)
                .check(classes);
    }

    public DescribedPredicate<JavaClass> hasMethodThrowException =
            new DescribedPredicate<JavaClass>("method throws exception"){
                @Override
                public boolean apply(JavaClass input) {
                    boolean result = false;
                    String throwPattern = "Method.+calls constructor.+Exception\\.<init>";
                    Pattern pattern = Pattern.compile(throwPattern);

                    Set<JavaConstructorCall> constructorCallsFromSelf = input.getConstructorCallsFromSelf();
                    for (JavaConstructorCall constructorCall: constructorCallsFromSelf) {
                        Matcher matcher = pattern.matcher(constructorCall.getDescription());
                        if (matcher.find()) {
                            result = true;
                            break;
                        }
                    }
                    return result;
                }
            };

    public ArchCondition<JavaClass> notPrintLogAndThrowException =
            new ArchCondition<JavaClass>("should not print log and throw exception at once") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    File file = new File("src/main/java/" + item.getPackageName() + "/" + item.getSimpleName() + ".java");
                    checkLineContentForLogAndException(item, events, file);
                }
            };

    private void checkLineContentForLogAndException(JavaClass item, ConditionEvents events, File file) {
        Scanner scanner = null;
        try {
             scanner = new Scanner(file);
        } catch (Exception e) {
            System.out.println("cannot find file");
        }
        try {
            String catchRegx = "catch\\s*\\([A-Za-z]*Exception";
            Pattern catchPattern = Pattern.compile(catchRegx);

            int lineNumber = 0;
            int endLineNumber = 0;
            int startLineNumber = 0;
            StringBuilder builder = null;
            int leftBracket, rightBracket = 0;

            while (scanner != null && scanner.hasNext()) {
                lineNumber++;
                String line = scanner.nextLine();
                Matcher catchMatcher = catchPattern.matcher(line);

                if (catchMatcher.find()) {
                    builder = new StringBuilder();
                    startLineNumber = lineNumber + 1;
                }
                if (builder != null) {
                    builder.append(line);
                    leftBracket = count(builder.toString(), '{');
                    rightBracket = count(builder.toString(), '}');

                    if (rightBracket > leftBracket) {
                        endLineNumber = lineNumber - 1;
                        String content = builder.toString();
                        if (content.contains("throw new") && content.contains("log.")) {
                            events.add(SimpleConditionEvent.violated(item,
                                    "print log and throw exception at once is not allowed from line " + startLineNumber
                                            + " to line " + endLineNumber + " in " + item.getSimpleName() + "" + ".java"));
                        }
                        builder = null;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("unknown error");
        }
    }

    public int count(String s, char c) {
        int result = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                result++;
            }
        }
        return result;
    }
}

