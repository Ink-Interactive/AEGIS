package atlanteshellsing.aegis.annotations;

import java.lang.annotation.*;

/**
 * Marks types, methods, or constructors to be excluded from Jacoco code coverage reports.
 * This is useful for generated code, boilerplate, or code that should not be counted
 * toward coverage metrics.
 *
 * <p>Usage example:
 * <pre>
 * {@code @ExcludeAsGenerated
 * public class GeneratedClass {
 *       // This class will be excluded from coverage
 *  }}
 * </pre>
 */
@Retention(RetentionPolicy.CLASS) // no runtime overhead needed
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
@ExcludeAsGenerated
public @interface ExcludeAsGenerated {}
