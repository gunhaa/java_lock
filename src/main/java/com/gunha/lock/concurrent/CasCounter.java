package com.gunha.lock.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * {@link AtomicInteger}로 만든 락 없는(lock-free) 카운터. CAS 재시도 루프를 직접 써 보고 횟수를 센다.
 *
 * <h2>CAS (compare-and-swap)</h2>
 * "메모리 값이 기대값과 같으면 새 값으로 바꾸고 true, 아니면 아무것도 안 하고 false"를 CPU 명령 하나로 원자적으로 한다.
 * <ul>
 * <li>x86: {@code lock cmpxchg}. {@code lock} 접두는 그 캐시 라인을 이 코어가 독점(MESI의 Modified/Exclusive)한 채
 *     읽기·비교·쓰기를 끝내게 한다. 동시에 full memory fence 역할도 한다.</li>
 * <li>ARM64: ARMv8.1 LSE의 {@code cas} 명령, 또는 {@code ldaxr}/{@code stlxr}(LL/SC) 루프.</li>
 * <li>자바에서는 {@code VarHandle}/{@code Unsafe}의 compareAndSet을 JIT이 이 명령으로 바꾼다(intrinsic).</li>
 * </ul>
 *
 * <h2>락과 비교</h2>
 * <ul>
 * <li>스레드가 잠들지 않는다. park도 OS 호출도 컨텍스트 스위치도 없다. 그래서 짧은 갱신에서는 락보다 빠르다.</li>
 * <li>대신 경합이 심하면 CAS 실패와 재시도가 늘고, 코어들이 같은 캐시 라인을 서로 뺏어 오느라(캐시 라인 핑퐁) 느려진다.
 *     이 경우가 {@link HitCounter}({@link LongAdder})의 영역이다.</li>
 * <li>변수 <b>하나</b>만 원자적으로 바꿀 수 있다. 여러 필드를 함께 바꿔야 하면 락이나 불변 객체 + {@code AtomicReference}를 쓴다.</li>
 * <li>ABA 문제: 값이 A → B → A로 바뀌면 CAS는 변화를 모른다. 필요하면 {@code AtomicStampedReference}로 버전을 붙인다.</li>
 * </ul>
 *
 * <h2>incrementAndGet은 루프가 아니다</h2>
 * {@link #incrementIntrinsic()}의 {@code incrementAndGet()}은 x86에서 JIT이 {@code lock xadd}(fetch-and-add)
 * 명령 하나로 바꾼다. 실패가 없으므로 재시도 루프도 없다. 직접 쓴 {@link #incrementWithCasLoop()}과 비교해 볼 것.
 *
 * <h2>테스트로 확인</h2>
 * CAS가 실패하면 다시 읽고 재시도하므로 값이 유실되지 않는다.
 * {@snippet file="com/gunha/lock/concurrent/CasCounterTest.java" region="cas-loop"}
 */
public class CasCounter {

	private final AtomicInteger value = new AtomicInteger();

	private final LongAdder casFailures = new LongAdder();

	/** 읽고 → 계산하고 → CAS로 쓰기를 시도한다. 그사이 누가 바꿨으면 실패하므로 처음부터 다시 한다. */
	public int incrementWithCasLoop() {
		while (true) {
			int current = value.get();
			int next = current + 1;
			if (value.compareAndSet(current, next)) {
				return next;
			}
			casFailures.increment();
		}
	}

	public int incrementIntrinsic() {
		return value.incrementAndGet();
	}

	public int get() {
		return value.get();
	}

	/** 지금까지 CAS가 실패해 다시 시도한 횟수. 경합 정도를 보여 준다. */
	public long casFailures() {
		return casFailures.sum();
	}

}
