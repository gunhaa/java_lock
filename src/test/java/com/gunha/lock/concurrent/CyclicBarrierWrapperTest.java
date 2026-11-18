package com.gunha.lock.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CyclicBarrierWrapperTest {

	@Test
	@DisplayName("라운드마다 모두 모인 뒤 합계가 기록되고, 배리어는 라운드마다 재사용된다")
	void sumsEachRound() throws InterruptedException {
		// @start region="rounds"
		// 작업자 i의 라운드 r 기여 = (i + 1) * (r + 1). 작업자 4명이면 라운드 합은 10, 20, 30.
		var totals = CyclicBarrierWrapper.awaitEachRound(4, 3, (worker, round) -> (worker + 1) * (round + 1));

		// 다음 라운드 값으로 덮어쓰기 전에 합계가 계산되지 않았다면 섞인 값이 나온다.
		assertThat(totals).containsExactly(10, 20, 30);
		// @end
	}

	@Test
	@DisplayName("한 명이 시간 초과로 빠지면 배리어가 깨지고, 기다리던 다른 스레드도 BrokenBarrierException을 받는다")
	void brokenBarrier() throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(3);
		Started<Integer> waiter = TestThreads.start("waiter", barrier::await);
		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);

		// 세 번째 참가자가 오지 않으므로 메인 스레드는 시간 초과된다.
		assertThatThrownBy(() -> barrier.await(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

		assertThat(barrier.isBroken()).isTrue();
		assertThatThrownBy(waiter::join).isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(BrokenBarrierException.class);

		barrier.reset();
		assertThat(barrier.isBroken()).isFalse();
	}

}
