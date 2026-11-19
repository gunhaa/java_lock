package com.gunha.lock.concurrent;

import java.util.concurrent.atomic.LongAdder;

/**
 * {@link LongAdder}를 감싼 래퍼. 조회수처럼 여러 스레드가 매우 자주 더하고, 합계는 가끔 읽는 경우에 맞는다.
 *
 * <h2>구조 (Striped64)</h2>
 * <ul>
 * <li>경합이 없을 때는 {@code base} 필드 하나에 CAS로 더한다. {@code AtomicLong}과 같다.</li>
 * <li>CAS가 실패하면(경합 발생) {@code Cell} 배열을 만들고, 스레드마다 해시(probe)로 고른 셀에 더한다.
 *     계속 충돌하면 배열을 CPU 수까지 2배씩 늘린다.</li>
 * <li>각 {@code Cell}은 {@code @Contended}로 앞뒤에 패딩을 둬서 서로 다른 캐시 라인에 놓인다. 다른 변수인데
 *     같은 캐시 라인에 있어 서로 무효화시키는 <b>거짓 공유(false sharing)</b>를 막는다.</li>
 * </ul>
 * 결과적으로 스레드마다 (대체로) 다른 캐시 라인에 쓰게 되어, {@code AtomicInteger}({@link AtomicIntegerWrapper})가 겪는 캐시 라인 핑퐁과 CAS 재시도가 거의 사라진다.
 *
 * <h2>대가</h2>
 * <ul>
 * <li>{@code sum()}은 {@code base}와 모든 셀을 더한다. 더하는 동안 다른 스레드가 계속 갱신하면, 그 결과는 특정 순간의
 *     정확한 스냅샷이 아니다. 갱신이 멈춘 뒤에 읽으면 정확하다.</li>
 * <li>셀 배열만큼 메모리를 더 쓴다. {@code incrementAndGet()}처럼 "더한 직후의 값"을 받을 수 없다.</li>
 * <li>그래서 시퀀스 번호 발급처럼 매번 정확한 값이 필요하면 {@code AtomicLong}, 통계·메트릭이면 {@code LongAdder}를 쓴다.</li>
 * </ul>
 *
 * <h2>테스트로 확인</h2>
 * 여러 셀에 나눠 더해도, 갱신이 멈춘 뒤의 합계는 정확하다.
 * {@snippet file="com/gunha/lock/concurrent/LongAdderWrapperTest.java" region="adder"}
 */
public class LongAdderWrapper {

	private final LongAdder adder = new LongAdder();

	/** {@link LongAdder#increment()}에 위임한다. */
	public void increment() {
		adder.increment();
	}

	/** {@link LongAdder#sum()}에 위임한다. 갱신이 멈춘 뒤에 읽어야 정확하다. */
	public long sum() {
		return adder.sum();
	}

}
