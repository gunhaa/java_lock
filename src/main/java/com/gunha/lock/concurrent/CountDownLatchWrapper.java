package com.gunha.lock.concurrent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link CountDownLatch}를 감싼 래퍼. 래치로 여러 작업을 동시에 출발시키고, 모두 끝날 때까지 {@code await}한다.
 *
 * <h2>동작 (AQS 공유 모드)</h2>
 * <ul>
 * <li>{@code state} = 남은 카운트.</li>
 * <li>{@code countDown()}: CAS로 1 줄인다. <b>기다리지 않는다.</b> 0이 되는 순간 대기자를 모두 깨운다.</li>
 * <li>{@code await()}: 0이 될 때까지 park, 상태는 {@code WAITING}. 이미 0이면 바로 돌아온다.</li>
 * <li><b>일회용</b>: 0에서 다시 올라가지 않는다. 반복해서 써야 하면 {@link java.util.concurrent.CyclicBarrier}나
 *     {@link java.util.concurrent.Phaser}를 쓴다.</li>
 * </ul>
 *
 * <h2>이 예제의 래치 세 개</h2>
 * <ol>
 * <li>{@code ready}(N): 모든 작업 스레드가 생성되어 출발선에 섰는지.</li>
 * <li>{@code startGate}(1): 출발 신호. 하나를 0으로 만들어 N개 스레드를 한꺼번에 깨운다.
 *     스레드 생성 시간 차이를 없애 실제 경합을 만들 때 쓰는 패턴이다.</li>
 * <li>{@code done}(N): 모든 작업 완료.</li>
 * </ol>
 *
 * <h2>메모리 가시성</h2>
 * {@code countDown()} 이전의 동작은 그 카운트다운 덕분에 돌아온 {@code await()} 이후보다 happens-before다.
 * 그래서 작업 스레드가 volatile이 아닌 배열에 쓴 결과도, {@code done.await()} 뒤의 호출자는 정확히 본다.
 *
 * <h2>테스트로 확인</h2>
 * {@code await}가 {@code true}를 돌려주면 작업이 쓴 결과가 호출자에게 보인다.
 * {@snippet file="com/gunha/lock/concurrent/CountDownLatchWrapperTest.java" region="latch"}
 */
public final class CountDownLatchWrapper {

	private CountDownLatchWrapper() {
	}

	/**
	 * tasks를 각자 스레드에서 동시에 출발시키고 {@code done.await(timeout, unit)}으로 완료를 기다린다.
	 *
	 * @return 시간 안에 모두 끝났으면 true ({@link CountDownLatch#await(long, TimeUnit)}의 결과)
	 */
	public static boolean runAllAndAwait(List<Runnable> tasks, long timeout, TimeUnit unit) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(tasks.size());

		for (Runnable task : tasks) {
			Thread.ofPlatform().daemon().start(() -> {
				ready.countDown();
				try {
					startGate.await();
					task.run();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}

		ready.await();
		startGate.countDown();
		return done.await(timeout, unit);
	}

}
