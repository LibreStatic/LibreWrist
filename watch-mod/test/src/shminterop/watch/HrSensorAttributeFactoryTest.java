package shminterop.watch;

/** Host tests for HrSensorAttributeFactory: reflective construction never throws. */
public final class HrSensorAttributeFactoryTest {
    public static final class Fixture {
        public Fixture() {}
    }

    public static final class NoNoArg {
        public NoNoArg(int unused) {}
    }

    public static final class Throwing {
        public Throwing() { throw new IllegalStateException("boom"); }
    }

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        check(HrSensorAttributeFactory.newInstance("com.example.DoesNotExistSensorAttribute") == null,
                "missing class name: returns null instead of throwing");
        check(HrSensorAttributeFactory.newInstance(Fixture.class) instanceof Fixture,
                "class with a no-arg constructor: returns an instance");
        check(HrSensorAttributeFactory.newInstance(Fixture.class.getName()) instanceof Fixture,
                "class name with a no-arg constructor: returns an instance");
        check(HrSensorAttributeFactory.newInstance(NoNoArg.class) == null,
                "class without a no-arg constructor: returns null");
        check(HrSensorAttributeFactory.newInstance(Throwing.class) == null,
                "constructor that throws: returns null");

        if (failures > 0) {
            System.exit(1);
        }
    }
}
