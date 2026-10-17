package android.os;

/** Compose 재구성 JVM 테스트에서 플랫폼 트레이싱만 생략한다. */
public final class Trace {
    private Trace() {}

    public static void beginSection(String name) {}

    public static void endSection() {}
}
