package reactor.core;

import org.slf4j.LoggerFactory;
import reactor.Fn;
import reactor.fn.*;
import reactor.fn.dispatch.Dispatcher;
import reactor.fn.dispatch.DispatcherAware;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static reactor.Fn.$;
import static reactor.core.Context.synchronousDispatcher;

/**
 * A {@literal Composable} is a way to provide components from other threads to act on incoming data and provide new
 * data to other components that must wait on the data to become available.
 *
 * @author Jon Brisbin
 * @author Andy Wilkinson
 * @author Stephane Maldini
 */
public class Composable<T> implements Consumer<T>, Supplier<T>, Deferred<T>, DispatcherAware {

	static final String   EXPECTED_ACCEPT_LENGTH_HEADER = "x-reactor-expectedAcceptCount";
	static       long     DEFAULT_TIMEOUT               = 30l;
	static       TimeUnit DEFAULT_TIMEUNIT              = TimeUnit.SECONDS;

	static {
		String s = System.getProperty("reactor.max.await.timeout");
		if (null != s && s.length() > 0) {
			TimeUnit unit = TimeUnit.SECONDS;
			if (s.endsWith("ns")) {
				s = s.substring(0, s.length() - 2);
				unit = TimeUnit.NANOSECONDS;
			} else if (s.endsWith("ms")) {
				s = s.substring(0, s.length() - 2);
				unit = TimeUnit.MILLISECONDS;
			} else if (s.endsWith("s")) {
				s = s.substring(0, s.length() - 1);
				unit = TimeUnit.SECONDS;
			}
			try {
				long l = Long.parseLong(s);
				DEFAULT_TIMEOUT = l;
				DEFAULT_TIMEUNIT = unit;
			} catch (NumberFormatException ignored) {
				LoggerFactory.getLogger(Composable.class).error(ignored.getMessage(), ignored);
			}
		}
	}

	protected final Object     monitor             = new Object();
	protected final Selector   accept              = $();
	protected final Selector   first               = $();
	protected final Selector   last                = $();
	protected final AtomicLong acceptedCount       = new AtomicLong(0);
	protected final AtomicLong expectedAcceptCount = new AtomicLong(-1);
	protected final    Observable observable;
	protected volatile Dispatcher dispatcher;
	protected boolean hasBlockers = false;
	protected T         value;
	protected Throwable error;

	/**
	 * Create a {@literal Composable} with default behavior.
	 */
	public Composable() {
		this.observable = createObservable(null);
	}

	/**
	 * Create a {@literal Composable} that uses the given {@link Reactor} for publishing events internally.
	 *
	 * @param observable The {@link Reactor} to use.
	 */
	public Composable(Observable observable) {
		this.observable = observable;
	}

	public static <T> Composable<T> from(Composable<T> src) {
		final Composable<T> c = new Composable<T>(src.observable);
		src.consume(new Consumer<T>() {
			@Override
			public void accept(T t) {
				c.accept(t);
			}
		});
		return c;
	}

	/**
	 * Create a {@literal Composable} from the given value.
	 *
	 * @param value The value to use.
	 * @param <T>   The type of the value.
	 * @return The new {@link Composable}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> Composable<T> from(T value) {
		return new DelayedAcceptComposable<T>(new Reactor(), Arrays.asList(value));
	}

	/**
	 * Create a {@literal Composable} from the given list of values.
	 *
	 * @param values The values to use.
	 * @param <T>    The type of the values.
	 * @return The new {@literal Composable}.
	 */
	public static <T> Composable<T> from(Iterable<T> values) {
		return new DelayedAcceptComposable<T>(new Reactor(), values);
	}

	/**
	 * Create a {@literal Composable} from the given {@link Selector} and {@link Event} and delay notification of the event
	 * on the given {@link Observable} until the returned {@link Composable}'s {@link #await(long,
	 * java.util.concurrent.TimeUnit)} or {@link #get()} methods are called.
	 *
	 * @param sel        The right-hand side of the {@link Selector} comparison.
	 * @param ev         The {@literal Event}.
	 * @param observable The {@link Observable} on which to invoke the notify method.
	 * @param <T>        The type of the {@link Event} data.
	 * @return The new {@literal Composable}.
	 */
	public static <T, E extends Event<T>> Composable<E> from(final Selector sel, E ev, final Observable observable) {
		return Composable.from(ev)
										 .consume(new Consumer<E>() {
											 @Override
											 public void accept(E e) {
												 observable.notify(sel, e, null);
											 }
										 });
	}

	@Override
	public Dispatcher getDispatcher() {
		return dispatcher;
	}

	@Override
	public Composable<T> setDispatcher(Dispatcher dispatcher) {
		this.dispatcher = dispatcher;
		if (observable instanceof DispatcherAware) {
			((DispatcherAware) observable).setDispatcher(dispatcher);
		}
		return this;
	}

	/**
	 * Set the number of times to expect {@link #accept(Object)} to be called.
	 *
	 * @param expectedAcceptCount The number of times {@link #accept(Object)} will be called.
	 * @return {@literal this}
	 */
	public Composable<T> setExpectedAcceptCount(long expectedAcceptCount) {
		this.expectedAcceptCount.set(expectedAcceptCount);
		if (this.acceptedCount.get() >= expectedAcceptCount) {
			observable.notify(last, Fn.event(value));
			synchronized (monitor) {
				monitor.notifyAll();
			}
		}
		return this;
	}

	/**
	 * Register a {@link Consumer} that will be invoked whenever {@link #accept(Object)} is called.
	 *
	 * @param consumer The consumer to invoke.
	 * @return {@literal this}
	 * @see {@link #accept(Object)}
	 */
	public Composable<T> consume(Consumer<T> consumer) {
		return when(accept, consumer);
	}

	/**
	 * Register a {@link Selector} and {@link Reactor} on which to publish an event whenever {@link #accept(Object)} is
	 * called.
	 *
	 * @param sel        The {@link Selector} to use when publishing the {@link Event}.
	 * @param observable The {@link Observable} on which to publish the {@link Event}.
	 * @return {@literal this}
	 */
	public Composable<T> consume(final Selector sel, final Observable observable) {
		return when(accept, new Consumer<T>() {
			@Override
			public void accept(T event) {
				observable.notify(sel, Event.class.isAssignableFrom(event.getClass()) ? (Event<?>) event : Fn.event(event));
			}
		});
	}

	/**
	 * Creates a new {@link Composable} that will be triggered once, the first time {@link #accept(Object)} is called on
	 * the parent.
	 *
	 * @return A new {@link Composable} that is linked to the parent.
	 */
	public Composable<T> first() {
		final Composable<T> c = createComposable(observable);
		c.expectedAcceptCount.set(1);
		when(first, new Consumer<T>() {
			@Override
			public void accept(T t) {
				c.accept(t);
			}
		});
		return c;
	}

	/**
	 * Creates a new {@link Composable} that will be triggered once, the last time {@link #accept(Object)} is called on the
	 * parent.
	 *
	 * @return A new {@link Composable} that is linked to the parent.
	 * @see {@link #setExpectedAcceptCount(long)}
	 */
	public Composable<T> last() {
		final Composable<T> c = createComposable(observable);
		c.expectedAcceptCount.set(1);
		when(last, new Consumer<T>() {
			@Override
			public void accept(T t) {
				c.accept(t);
			}
		});
		return c;
	}

	/**
	 * Register a {@link Consumer} to be invoked whenever an exception that is assignable from the given exception type.
	 *
	 * @param exceptionType The type of exception to handle. Also matches an subclass of this type.
	 * @param onError       The {@link Consumer} to invoke when this error occurs.
	 * @param <E>           The type of exception.
	 * @return {@literal this}
	 */
	public <E extends Throwable> Composable<T> when(Class<E> exceptionType, final Consumer<E> onError) {
		observable.on(Fn.T(exceptionType), new Consumer<Event<E>>() {
			@Override
			public void accept(Event<E> ev) {
				onError.accept(ev.getData());
			}
		});
		return this;
	}

	/**
	 * Create a new {@link Composable} that is linked to the parent through the given {@link Function}. When the parent's
	 * {@link #accept(Object)} is invoked, this {@link Function} is invoked and the result is passed into the returned
	 * {@link Composable}.
	 *
	 * @param fn  The transformation function to apply.
	 * @param <V> The type of the object returned from the given {@link Function}.
	 * @return The new {@link Composable}.
	 */
	public <V> Composable<V> map(final Function<T, V> fn) {
		final Composable<V> c = createComposable(createObservable(observable));
		when(accept, new Consumer<T>() {
			@Override
			public void accept(T value) {
				try {
					c.accept(fn.apply(value));
				} catch (Throwable t) {
					// Errors should be reported on the returned Composable, not the parent.
					c.observable.notify(Fn.T(t.getClass()), Fn.event(t));
					c.decreaseAcceptLength();
				}
			}
		});
		return c;
	}

	/**
	 * Create a new {@link Composable} that is linked to the parent through the given {@link Selector} and {@link
	 * Observable}. When the parent's {@link #accept(Object)} is invoked, its value is wrapped into an {@link Event} and
	 * passed to {@link Observable#notify (reactor.fn.Event)} along with the given {@link Selector}. After the event is
	 * being propagated to the reactor consumers, the new composition expects {@param <V>} replies to be returned - A
	 * consumer might reply using {@link R#replyTo(reactor.fn.Event, reactor.fn.Event)}. }
	 *
	 * @param sel        The selector to notify
	 * @param observable The observable to notify
	 * @param <V>        The type of the object returned by reactor reply.
	 * @return The new {@link Composable}.
	 */
	public <V> Composable<V> map(final Selector sel, final Observable observable) {
		/* todo look if we may pick the size from consumerRegistry or just use a unbounded length */
		final Composable<V> c = new DelayedAcceptComposable<V>(observable, -1);
		final Selector replyTo = $();

		observable.on(replyTo, new Consumer<Event<V>>() {
			@Override
			public void accept(Event<V> event) {
				try {
					c.accept(event.getData());
				} catch (Throwable t) {
					c.observable.notify(Fn.T(t.getClass()), Fn.event(t));
					c.decreaseAcceptLength();
				}
			}
		});

		when(accept, new Consumer<T>() {
			@Override
			public void accept(T value) {
				try {
					Event<?> event = Event.class.isAssignableFrom(value.getClass()) ? (Event<?>) value : Fn.event(value);
					event.setReplyTo(replyTo);
					//event.getHeaders().setOrigin(reactor.getId());
					observable.notify(sel, event);
				} catch (Throwable t) {
					c.observable.notify(Fn.T(t.getClass()), Fn.event(t));
					c.decreaseAcceptLength();
				}
			}
		});
		return c;
	}

	/**
	 * Accumulate a result until expected accept count has been reached - If this limit hasn't been set, each accumulated
	 * result will notify the returned {@link Composable}. A {@link Function} taking a {@link Reduce} argument must be
	 * passed to process each pair formed of the last accumulated result and a new value to be processed.
	 *
	 * @param fn      The reduce function
	 * @param initial The initial accumulated result value e.g. an empty list.
	 * @param <V>     The type of the object returned by reactor reply.
	 * @return The new {@link Composable}.
	 */
	public <V> Composable<V> reduce(final Function<Reduce<T, V>, V> fn, V initial) {
		final AtomicReference<V> lastValue = new AtomicReference<V>(initial);
		final Composable<V> c = createComposable(createObservable(observable));
		c.setExpectedAcceptCount(1);
		when(accept, new Consumer<T>() {
			@Override
			public void accept(T value) {
				try {
					Reduce<T, V> r = new Reduce<T, V>(lastValue.get(), value);
					lastValue.set(fn.apply(r));
					if (expectedAcceptCount.get() < 0) {
						c.accept(lastValue.get());
					}
				} catch (Throwable t) {
					// Errors should be reported on the returned Composable, not the parent.
					c.observable.notify(Fn.T(t.getClass()), Fn.event(t));
					c.decreaseAcceptLength();
				}
			}
		});
		when(last, new Consumer<T>() {
			@Override
			public void accept(T t) {
				c.accept(lastValue.get());
			}
		});
		return c;
	}

	/**
	 * Accumulate a result until expected accept count has been reached - If this limit hasn't been set, each accumulated
	 * result will notify the returned {@link Composable}. A {@link Function} taking a {@link Reduce} argument must be
	 * passed to process each pair formed of the last accumulated result and a new value to be processed.
	 *
	 * @param fn  The reduce function
	 * @param <V> The type of the object returned by reactor reply.
	 * @return The new {@link Composable}.
	 */
	public <V> Composable<V> reduce(final Function<Reduce<T, V>, V> fn) {
		return reduce(fn, null);
	}


	/**
	 * Selectively call the returned {@link Composable} depending on the predicate {@link Function} argument
	 *
	 * @param fn The filter function, taking argument {@param <T>} and returning a {@link Boolean}
	 * @return The new {@link Composable}.
	 */
	public Composable<T> filter(final Function<T, Boolean> fn) {
		final Composable<T> c = createComposable(createObservable(observable));
		when(accept, new Consumer<T>() {
			@Override
			public void accept(T value) {
				try {
					if (fn.apply(value)) {
						c.accept(value);
					} else {
						c.decreaseAcceptLength();
					}
				} catch (Throwable t) {
					// Errors should be reported on the returned Composable, not the parent.
					c.observable.notify(Fn.T(t.getClass()), Fn.event(t));
					c.decreaseAcceptLength();
				}
			}
		});
		return c;
	}


	/**
	 * Trigger composition with an exception to be processed by dedicated consumers
	 *
	 * @param error The exception
	 */
	public void accept(Throwable error) {
		synchronized (monitor) {
			this.error = error;
			if (hasBlockers) {
				monitor.notifyAll();
			}
		}
		observable.notify(Fn.T(error.getClass()), Fn.event(error));
	}

	/**
	 * Trigger composition with a value to be processed by dedicated consumers
	 *
	 * @param value The exception
	 */
	public void accept(T value) {
		synchronized (monitor) {
			this.value = value;
			if (hasBlockers) {
				monitor.notifyAll();
			}
		}
		observable.notify(accept, Fn.event(value));
		acceptedCount.incrementAndGet();
	}

	@Override
	public T await() throws InterruptedException {
		return await(DEFAULT_TIMEOUT, DEFAULT_TIMEUNIT);
	}

	@Override
	public T await(long timeout, TimeUnit unit) throws InterruptedException {
		synchronized (monitor) {
			if (isComplete()) {
				return get();
			}
			if (timeout >= 0) {
				hasBlockers = true;
				long msTimeout = TimeUnit.MILLISECONDS.convert(timeout, unit);
				long endTime = System.currentTimeMillis() + msTimeout;
				long now;
				while (!isComplete() && (now = System.currentTimeMillis()) < endTime) {
					this.monitor.wait(endTime - now);
				}
			} else {
				while (!isComplete()) {
					this.monitor.wait();
				}
			}
			hasBlockers = false;
		}
		return get();
	}

	private boolean isComplete() {
		long expectedAcceptCount = this.expectedAcceptCount.get();
		return null != error || (null != value && expectedAcceptCount >= 0 && acceptedCount.get() >= expectedAcceptCount);
	}

	@Override
	public T get() {
		synchronized (this.monitor) {
			if (null != error) {
				throw new IllegalStateException(error);
			}
			return value;
		}
	}

	protected Composable<T> when(Selector sel, final Consumer<T> consumer) {

		Consumer<Event<T>> whenConsumer = new Consumer<Event<T>>() {
			@Override
			public void accept(Event<T> ev) {
				consumer.accept(ev.getData());
			}
		};

		if (sel == accept && value != null) {
			R.schedule(consumer, value, observable);
		} else {
			observable.on(sel, whenConsumer);
		}
		return this;
	}

	protected Observable createObservable(Observable src) {
		if (null == src) {
			return new Reactor();
		}
		if (src instanceof Reactor) {
			return new Reactor((Reactor) src).setDispatcher(synchronousDispatcher());
		} else if (src instanceof DispatcherAware) {
			return new Reactor().setDispatcher(((DispatcherAware) src).getDispatcher());
		} else {
			return new Reactor();
		}
	}

	protected <T> Composable<T> createComposable(Observable src) {
		Composable<T> c = new Composable<T>(src);
		c.expectedAcceptCount.set(expectedAcceptCount.get());
		return c;
	}

	protected void decreaseAcceptLength() {
		if (expectedAcceptCount.decrementAndGet() <= acceptedCount.get()) {
			synchronized (monitor) {
				monitor.notifyAll();
			}
		}
	}

	/**
	 * A {@link #reduce(reactor.fn.Function)} operation needs a stateful object to pass as the argument, which contains the
	 * last accumulated value, as well as the next, just-accepted value.
	 *
	 * @param <T> The type of the input value.
	 * @param <V> The type of the accumulated or last value.
	 */
	public static class Reduce<T, V> {
		private final V lastValue;
		private final T nextValue;

		public Reduce(V lastValue, T nextValue) {
			this.lastValue = lastValue;
			this.nextValue = nextValue;
		}

		/**
		 * Get the accumulated value.
		 *
		 * @return
		 */
		public V getLastValue() {
			return lastValue;
		}

		/**
		 * Get the next input value.
		 *
		 * @return
		 */
		public T getNextValue() {
			return nextValue;
		}
	}

	private static class DelayedAcceptComposable<T> extends Composable<T> {
		private final Object stateMonitor = new Object();
		protected final Iterable<T> values;
		protected AcceptState acceptState = AcceptState.DELAYED;

		protected DelayedAcceptComposable(Observable src, Iterable<T> values) {
			super(src);
			this.values = values;
			if (values instanceof Collection) {
				expectedAcceptCount.set(((Collection) values).size());
			}
		}

		protected DelayedAcceptComposable(Observable src, long length) {
			super(src);
			expectedAcceptCount.set(length);
			this.values = null;
		}

		@Override
		public void accept(Throwable error) {
			synchronized (monitor) {
				this.error = error;
			}
			observable.notify(Fn.T(error.getClass()), Fn.event(error));
		}

		@Override
		public void accept(T value) {
			synchronized (monitor) {
				this.value = value;
			}
			acceptedCount.incrementAndGet();

			Event<T> ev = Fn.event(value);
			ev.getHeaders().set(EXPECTED_ACCEPT_LENGTH_HEADER, String.valueOf(expectedAcceptCount.get()));

			if (acceptedCount.get() == 1) {
				observable.notify(first, ev);
			}

			observable.notify(accept, ev);

			if (acceptedCount.get() == expectedAcceptCount.get()) {
				observable.notify(last, ev);
				synchronized (monitor) {
					monitor.notifyAll();
				}
			}
		}

		@Override
		public T await(long timeout, TimeUnit unit) throws InterruptedException {
			delayedAccept();
			return super.await(timeout, unit);
		}

		@Override
		public T get() {
			delayedAccept();
			return super.get();
		}

		@Override
		@SuppressWarnings("unchecked")
		protected <T> Composable<T> createComposable(Observable src) {
			final DelayedAcceptComposable<T> self = (DelayedAcceptComposable<T>) this;
			return new DelayedAcceptComposable<T>(src, self.expectedAcceptCount.get()) {
				@Override
				protected void delayedAccept() {
					self.delayedAccept();
				}
			};
		}

		protected void delayedAccept() {
			Throwable localError = null;
			Iterable<T> localValues = null;
			T localValue = null;

			boolean acceptRequired = false;

			synchronized (this.stateMonitor) {
				if (acceptState == AcceptState.ACCEPTED) {
					return;
				} else if (acceptState == AcceptState.DELAYED) {
					synchronized (this.monitor) {
						localError = error;
						localValue = value;
						localValues = values;
					}
					acceptState = AcceptState.ACCEPTING;
					acceptRequired = true;
				} else {
					while (acceptState == AcceptState.ACCEPTING) {
						try {
							stateMonitor.wait();
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							break;
						}
					}
				}
			}

			if (acceptRequired) {
				if (null != localError) {
					accept(localError);
				} else if (null != localValues) {
					for (T t : localValues) {
						accept(t);
					}
				} else if (null != localValue) {
					accept(localValue);
				}
				synchronized (stateMonitor) {
					acceptState = AcceptState.ACCEPTED;
					stateMonitor.notifyAll();
				}
			}
		}

		private static enum AcceptState {
			DELAYED, ACCEPTING, ACCEPTED;
		}
	}

}