package com.gunha.lock.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 동시성 테스트용 도우미.
 * <p>
 * {@code Thread.sleep()} 후 결과를 보는 방식은 타이밍에 따라 실패하므로,
 * 상대 스레드가 원하는 상태({@link Thread.State})에 들어갈 때까지 폴링해서 "지금 락을 기다리는 중"임을 확인한다.
 */
public final class TestThreads {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** runConcurrently 전체 제한 시간. 테스트 클래스의 {@code @Timeout(30)}보다 먼저 걸려야 원인이 보인다. */
	private static final Duration CONCURRENT_TIMEOUT = Duration.ofSeconds(20);

	private TestThreads() {
	}

	/** 시작한 스레드와 그 결과. */
	public record Started<T>(Thread thread, FutureTask<T> result) {

		/** 작업이 끝날 때까지 기다려 결과를 돌려준다. 작업에서 난 예외는 {@code ExecutionException}의 원인으로 전달된다. */
		public T join() throws Exception {
			return result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	/** 이름 붙은 데몬 플랫폼 스레드에서 작업을 시작한다. */
	public static <T> Started<T> start(String name, Callable<T> task) {
		FutureTask<T> future = new FutureTask<>(task);
		Thread thread = Thread.ofPlatform().name(name).daemon().start(future);
		return new Started<>(thread, future);
	}

	/** 스레드가 기대한 상태가 될 때까지 기다린다. */
	public static void awaitState(Thread thread, Thread.State expected) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (thread.getState() != expected) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError(
						thread.getName() + " 스레드가 " + expected + " 상태가 되지 않음 (현재 " + thread.getState() + ")");
			}
			Thread.sleep(1);
		}
	}

	/** 람다 안에서 쓰기 위해 검사 예외 없이 래치를 기다린다. */
	public static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new AssertionError("래치 대기 시간 초과");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * threads개 작업을 동시에 출발시켜 각자 action을 iterations번 실행하고, 모두 끝날 때까지 기다린다.
	 * <p>
	 * 작업에서 난 예외는 {@link AssertionError}로 다시 던지고, 시간 안에 끝나지 않아도 실패시킨다.
	 * 정상적으로 돌아오면 {@code Future.get()} 덕분에 작업 안의 모든 쓰기가 호출자에게 보인다(happens-before).
	 */
	public static void runConcurrently(int threads, int iterations, Runnable action) throws InterruptedException {
		CountDownLatch startGate = new CountDownLatch(1);
		// 풀 크기가 작업 수보다 작으면 일부 작업이 큐에서 기다려 동시에 돌지 않으므로 같게 맞춘다.
		// 데몬 스레드라서 멈춘 작업이 남아도 테스트 JVM 종료를 막지 않는다.
		ExecutorService pool = Executors.newFixedThreadPool(threads,
				Thread.ofPlatform().name("worker-", 0).daemon().factory());
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					awaitLatch(startGate);
					for (int j = 0; j < iterations; j++) {
						action.run();
					}
				}));
			}
			startGate.countDown();

			long deadline = System.nanoTime() + CONCURRENT_TIMEOUT.toNanos();
			for (Future<?> future : futures) {
				future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
		}
		catch (ExecutionException ex) {
			throw new AssertionError("작업 중 예외 발생", ex.getCause());
		}
		catch (TimeoutException ex) {
			throw new AssertionError("작업이 " + CONCURRENT_TIMEOUT.toSeconds() + "초 안에 끝나지 않음", ex);
		}
		finally {
			// close()는 모든 작업이 끝날 때까지(인터럽트를 받아도) 기다리므로, 멈춘 작업이 있으면 테스트도 같이 멈춘다.
			// shutdownNow()는 남은 작업을 인터럽트하고 바로 돌아온다.
			pool.shutdownNow();
		}
	}

}
