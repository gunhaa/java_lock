package com.gunha.lock.concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.function.IntBinaryOperator;

/**
 * {@link CyclicBarrier}로 여러 작업자가 라운드마다 모두 모인 뒤 다음 라운드로 넘어가게 한다.
 * (예: 시뮬레이션의 한 스텝, 병렬 계산의 단계별 합치기)
 *
 * <h2>동작</h2>
 * <ul>
 * <li>{@code await()}: 도착 수를 세고, 마지막(parties번째)이 아니면 {@code Condition.await()}로 잠든다({@code WAITING}).</li>
 * <li>마지막 도착자는 <b>자기 스레드에서</b> barrier action을 실행한 뒤 {@code signalAll()}로 모두 깨운다.
 *     action이 끝나기 전에는 아무도 다음 라운드로 가지 않으므로, action은 이번 라운드 결과를 안전하게 합칠 수 있다.</li>
 * <li><b>재사용</b>: 모두 풀려나면 새 세대(Generation)로 넘어가 카운트가 다시 parties가 된다. CountDownLatch와 다른 점이다.</li>
 * <li>구현은 AQS를 직접 쓰지 않고 {@code ReentrantLock} 하나와 {@code Condition} 하나로 되어 있다.</li>
 * </ul>
 *
 * <h2>깨진 배리어(broken)</h2>
 * 기다리던 스레드 하나가 인터럽트되거나 시간 초과되거나 barrier action이 예외를 던지면 배리어가 깨진다.
 * 그러면 기다리던 다른 모든 스레드도 {@link BrokenBarrierException}을 받는다. 한 명이 빠지면 영원히 모일 수 없으니
 * 전원을 깨워서 실패를 알리는 설계다. 다시 쓰려면 {@code reset()}한다.
 *
 * <h2>메모리 가시성</h2>
 * 각 스레드의 {@code await()} 이전 동작 → barrier action → 다른 스레드들의 {@code await()} 이후 순서로 happens-before가 이어진다.
 * 그래서 아래 {@code contributions} 배열은 volatile이 아니어도 된다.
 *
 * <h2>테스트로 확인</h2>
 * 라운드마다 모두 모인 뒤 합계를 계산하고, 배리어를 다시 쓴다.
 * {@snippet file="com/gunha/lock/concurrent/PhaseRunnerTest.java" region="rounds"}
 */
public final class PhaseRunner {

	private PhaseRunner() {
	}

	/**
	 * parties개 작업자가 rounds번 계산한다. 라운드마다 작업자 i가 {@code work(i, round)}를 계산하고,
	 * 모두 모이면 그 라운드의 합을 기록한다.
	 *
	 * @return 라운드별 합계
	 */
	public static List<Integer> run(int parties, int rounds, IntBinaryOperator work) throws InterruptedException {
		int[] contributions = new int[parties];
		List<Integer> totals = new ArrayList<>();
		CyclicBarrier barrier = new CyclicBarrier(parties, () -> totals.add(Arrays.stream(contributions).sum()));

		List<Thread> workers = new ArrayList<>();
		for (int i = 0; i < parties; i++) {
			int worker = i;
			workers.add(Thread.ofPlatform().daemon().start(() -> {
				try {
					for (int round = 0; round < rounds; round++) {
						contributions[worker] = work.applyAsInt(worker, round);
						barrier.await();
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				catch (BrokenBarrierException ex) {
					throw new IllegalStateException("배리어가 깨졌습니다", ex);
				}
			}));
		}
		for (Thread worker : workers) {
			worker.join();
		}
		return totals;
	}

}
