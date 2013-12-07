package org.powerbot.script.internal;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.powerbot.bot.SelectiveEventQueue;
import org.powerbot.event.EventDispatcher;
import org.powerbot.event.debug.ViewMouse;
import org.powerbot.event.debug.ViewMouseTrails;
import org.powerbot.script.Script;
import org.powerbot.script.internal.scripts.Antipattern;
import org.powerbot.script.internal.scripts.BankPin;
import org.powerbot.script.internal.scripts.Login;
import org.powerbot.script.internal.scripts.StatTracker;
import org.powerbot.script.internal.scripts.TicketDestroy;
import org.powerbot.script.internal.scripts.WidgetCloser;
import org.powerbot.script.methods.MethodContext;
import org.powerbot.script.wrappers.Validatable;
import org.powerbot.service.scripts.ScriptBundle;
import org.powerbot.service.scripts.ScriptDefinition;
import org.powerbot.util.Tracker;

public final class ScriptController implements Runnable, Validatable, Script.Controller {
	public static final String TIMEOUT_PROPERTY = "script.timeout";

	private final MethodContext ctx;
	private final EventDispatcher dispatcher;
	private final AtomicReference<ThreadPoolExecutor> executor;
	private final Queue<Script> scripts;
	private final Class<? extends Script>[] daemons;
	private final AtomicReference<Thread> timeout;
	private final Runnable suspension;
	private final AtomicBoolean started, suspended, stopping;

	public final AtomicReference<ScriptBundle> bundle;

	public ScriptController(final MethodContext ctx, final EventDispatcher dispatcher) {
		this.ctx = ctx;
		this.dispatcher = dispatcher;

		executor = new AtomicReference<ThreadPoolExecutor>(null);
		timeout = new AtomicReference<Thread>(null);
		started = new AtomicBoolean(false);
		suspended = new AtomicBoolean(false);
		stopping = new AtomicBoolean(false);

		bundle = new AtomicReference<ScriptBundle>(null);

		daemons = new Class[] {
				Login.class,
				WidgetCloser.class,
				TicketDestroy.class,
				BankPin.class,
				Antipattern.class,
				StatTracker.class,
		};
		scripts = new PriorityQueue<Script>(daemons.length + 1);

		suspension = new Runnable() {
			@Override
			public void run() {
				while (isSuspended()) {
					try {
						Thread.sleep(600);
					} catch (final InterruptedException ignored) {
					}
				}
			}
		};
	}

	@Override
	public boolean isValid() {
		return started.get() && !stopping.get();
	}

	@Override
	public void run() {
		if (bundle.get() == null) {
			throw new IllegalStateException("bundle not set");
		}

		if (!started.compareAndSet(false, true)) {
			return;
		}

		final SelectiveEventQueue eq = SelectiveEventQueue.getInstance();
		if (!eq.isBlocking()) {
			eq.setBlocking(true);
		}

		executor.set(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.NANOSECONDS, new LinkedBlockingDeque<Runnable>()));

		final String s = ctx.properties.getProperty(TIMEOUT_PROPERTY, "");
		if (s != null) {
			long l = 0;
			try {
				l = Long.parseLong(s);
			} catch (final NumberFormatException ignored) {
			}

			final long m = l + 1000;

			if (l > 0) {
				final Thread t = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							Thread.sleep(m);
						} catch (final InterruptedException ignored) {
							return;
						}
						stop();
					}
				});

				t.setPriority(Thread.MIN_PRIORITY);
				t.setDaemon(true);
				t.start();
				timeout.set(t);
			}
		}

		if (!(dispatcher.contains(ViewMouse.class) || dispatcher.contains(ViewMouseTrails.class))) {
			dispatcher.add(new ViewMouseTrails(ctx));
		}

		final BlockingQueue<Runnable> queue = executor.get().getQueue();

		for (final Class<? extends Script> d : daemons) {
			queue.offer(new ScriptBootstrap(d));
		}

		queue.offer(new ScriptBootstrap(bundle.get().script));

		queue.offer(new Runnable() {
			@Override
			public void run() {
				call(Script.State.START);
			}
		});

		executor.get().submit(queue.poll());
	}

	private final class ScriptBootstrap implements Runnable {
		private final Class<? extends Script> clazz;

		public ScriptBootstrap(final Class<? extends Script> clazz) {
			this.clazz = clazz;
		}

		@Override
		public void run() {
			final Script s;
			try {
				new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							Script.controllerProxy.put(ScriptController.this);
						} catch (final InterruptedException ignored) {
						}
					}
				}).start();
				s = clazz.newInstance();
				bundle.get().instance.set(s);
			} catch (final Exception e) {
				e.printStackTrace();
				stop();
				return;
			}
			scripts.add(s);
			dispatcher.add(s);
			if (!s.getExecQueue(Script.State.START).contains(s)) {
				s.getExecQueue(Script.State.START).add(s);
			}
		}
	}

	@Override
	public boolean isStopping() {
		return stopping.get();
	}

	@Override
	public void stop() {
		if (!(started.get() && stopping.compareAndSet(false, true))) {
			return;
		}

		final Thread t = timeout.getAndSet(null);
		if (t != null) {
			t.interrupt();
		}

		call(Script.State.STOP);
		for (final Script s : scripts) {
			dispatcher.remove(s);
		}
		executor.get().shutdown();
		try {
			if (!executor.get().awaitTermination(10, TimeUnit.SECONDS)) {
				executor.get().shutdownNow();
			}
		} catch (final InterruptedException ignored) {
		}
		executor.set(null);
		scripts.clear();

		final SelectiveEventQueue eq = SelectiveEventQueue.getInstance();
		if (eq.isBlocking()) {
			eq.setBlocking(false);
		}

		suspended.set(false);
		stopping.set(false);
		started.set(false);
	}

	@Override
	public boolean isSuspended() {
		return suspended.get();
	}

	@Override
	public void suspend() {
		if (suspended.compareAndSet(false, true)) {
			call(Script.State.SUSPEND);

			final SelectiveEventQueue eq = SelectiveEventQueue.getInstance();
			if (eq.isBlocking()) {
				eq.setBlocking(false);
			}
		}
	}

	@Override
	public void resume() {
		if (suspended.compareAndSet(true, false)) {
			call(Script.State.RESUME);

			final SelectiveEventQueue eq = SelectiveEventQueue.getInstance();
			if (!eq.isBlocking()) {
				eq.setBlocking(true);
			}
		}
	}

	@Override
	public BlockingQueue<Runnable> getExecutor() {
		return executor.get().getQueue();
	}

	@Override
	public MethodContext getContext() {
		return ctx;
	}

	private void call(final Script.State state) {
		track(state);
		final BlockingQueue<Runnable> queue = executor.get().getQueue();

		for (final Script s : scripts) {
			for (final Runnable r : s.getExecQueue(state)) {
				queue.offer(r);
			}
		}

		if (state == Script.State.SUSPEND) {
			queue.offer(suspension);
		}

		if (!queue.isEmpty()) {
			executor.get().submit(queue.poll());
		}
	}

	private void track(final Script.State state) {
		final ScriptDefinition def = bundle.get().definition;
		if (def == null || def.getName() == null || (!def.local && (def.getID() == null || def.getID().isEmpty()))) {
			return;
		}

		String action = "";

		switch (state) {
		case START:
			break;
		case SUSPEND: {
			action = "pause";
			break;
		}
		case RESUME: {
			action = "resume";
			break;
		}
		case STOP: {
			action = "stop";
			break;
		}
		}

		final String page = String.format("scripts/%s/%s", def.local ? ScriptDefinition.LOCALID : def.getID(), action);
		Tracker.getInstance().trackPage(page, def.getName());
	}
}
