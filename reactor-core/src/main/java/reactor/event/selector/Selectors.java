package reactor.event.selector;

import reactor.function.Predicate;
import reactor.tuple.Tuple;
import reactor.tuple.Tuple2;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper methods for creating {@link Selector}s.
 *
 * @author Andy Wilkinson
 */
public abstract class Selectors {

	private static final AtomicInteger HASH_CODES = new AtomicInteger(Integer.MIN_VALUE);

	/**
	 * Creates an anonymous {@link reactor.event.selector.Selector}, returning a {@link Tuple}
	 * containing the {@link Selector} and the notification key that the selector matches.
	 *
	 * @return The Selector notification key tuple
	 *
	 * @see ObjectSelector
	 */
	public static Tuple2<Selector, Object> anonymous() {
		Object obj = new Object() {
			private final int hashCode = HASH_CODES.getAndIncrement() << 2;

			@Override
			public int hashCode() {
				return hashCode;
			}
		};
		return Tuple.of($(obj), obj);
	}

	/**
	 * A short-hand alias for {@link Selectors#anonymous()}.
	 * <p/>
	 * Creates an anonymous {@link reactor.event.selector.Selector}, returning a {@link Tuple}
	 * containing the {@link Selector} and the notification key that the selector matches.
	 *
	 * @return The Selector notification key tuple
	 *
	 * @see ObjectSelector
	 */
	public static Tuple2<Selector, Object> $() {
		return anonymous();
	}

	/**
	 * Creates a {@link Selector} based on the given object.
	 *
	 * @param obj
	 * 		The object to use for matching
	 *
	 * @return The new {@link ObjectSelector}.
	 *
	 * @see ObjectSelector
	 */
	public static <T> Selector object(T obj) {
		return new ObjectSelector<T>(obj);
	}

	/**
	 * A short-hand alias for {@link Selectors#object}.
	 * <p/>
	 * Creates a {@link Selector} based on the given object.
	 *
	 * @param obj
	 * 		The object to use for matching
	 *
	 * @return The new {@link ObjectSelector}.
	 *
	 * @see ObjectSelector
	 */
	public static <T> Selector $(T obj) {
		return object(obj);
	}

	/**
	 * Creates a {@link Selector} based on the given string format and arguments.
	 *
	 * @param fmt
	 * 		The {@code String.format} style format specification
	 * @param args
	 * 		The format args
	 *
	 * @return The new {@link ObjectSelector}.
	 *
	 * @see ObjectSelector
	 * @see String#format(String, Object...)
	 */
	public static Selector $(String fmt, Object... args) {
		return object(String.format(fmt, args));
	}

	/**
	 * Creates a {@link Selector} based on the given regular expression.
	 *
	 * @param regex
	 * 		The regular expression to compile and use for matching
	 *
	 * @return The new {@link RegexSelector}.
	 *
	 * @see RegexSelector
	 */
	public static Selector regex(String regex) {
		return new RegexSelector(regex);
	}

	/**
	 * A short-hand alias for {@link Selectors#regex(String)}.
	 * <p/>
	 * Creates a {@link Selector} based on the given regular expression.
	 *
	 * @param regex
	 * 		The regular expression to compile and use for matching
	 *
	 * @return The new {@link RegexSelector}.
	 *
	 * @see RegexSelector
	 */
	public static Selector R(String regex) {
		return new RegexSelector(regex);
	}

	/**
	 * Creates a {@link Selector} based on the given class type that matches objects whose type is
	 * assignable according to {@link Class#isAssignableFrom(Class)}.
	 *
	 * @param supertype
	 * 		The supertype to use for matching
	 *
	 * @return The new {@link ClassSelector}.
	 *
	 * @see ClassSelector
	 */
	public static Selector type(Class<?> supertype) {
		return new ClassSelector(supertype);
	}

	/**
	 * A short-hand alias for {@link Selectors#type(Class)}.
	 * <p/>
	 * Creates a {@link Selector} based on the given class type that matches objects whose type is
	 * assignable according to {@link Class#isAssignableFrom(Class)}.
	 *
	 * @param supertype
	 * 		The supertype to compare.
	 *
	 * @return The new {@link ClassSelector}.
	 *
	 * @see ClassSelector
	 */
	public static Selector T(Class<?> supertype) {
		return type(supertype);
	}

	/**
	 * Creates a {@link Selector} based on a URI template.
	 *
	 * @param uri
	 * 		The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link UriPathSelector}.
	 *
	 * @see UriPathTemplate
	 * @see UriPathSelector
	 */
	public static Selector uri(String uri) {
		if(null == uri) {
			return null;
		}
		switch(uri.charAt(0)) {
			case '/':
				return new UriPathSelector(uri);
			default:
				return new UriSelector(uri);
		}
	}

	/**
	 * A short-hand alias for {@link Selectors#uri(String)}.
	 * <p/>
	 * Creates a {@link Selector} based on a URI template.
	 *
	 * @param uri
	 * 		The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link UriPathSelector}.
	 *
	 * @see UriPathTemplate
	 * @see UriPathSelector
	 */
	public static Selector U(String uri) {
		return uri(uri);
	}

	/**
	 * Creates a {@link Selector} based on the given {@link Predicate}.
	 *
	 * @param predicate
	 * 		The {@link Predicate} to delegate to when matching objects.
	 *
	 * @return PredicateSelector
	 *
	 * @see PredicateSelector
	 */
	public static Selector predicate(Predicate<Object> predicate) {
		return new PredicateSelector(predicate);
	}

}
