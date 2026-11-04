package com.fgc.emulator;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 실행: gradle run --args="--scenario NIGHT_HYPO --speed 60 --port 8090"
 * 자격증명은 환경변수 EMU_USERNAME / EMU_PASSWORD. 없으면 임의 비밀번호를 만들어 콘솔에 한 번 출력한다.
 */
public final class EmulatorMain {
    public static void main(String[] args) throws Exception {
        Scenario scenario = Scenario.NORMAL;
        double speed = 1;
        int port = 8090;
        long seed = 42;
        String host = "127.0.0.1";
        double dup = 0, err = 0;
        long sensorAgeHours = 24;
        for (int i = 0; i < args.length; i += 2) {
            String v = args[i + 1];
            switch (args[i]) {
                case "--scenario" -> scenario = Scenario.valueOf(v);
                case "--speed" -> speed = Double.parseDouble(v);
                case "--port" -> port = Integer.parseInt(v);
                case "--seed" -> seed = Long.parseLong(v);
                case "--host" -> host = v;
                case "--duplicate-rate" -> dup = Double.parseDouble(v);
                case "--error-rate" -> err = Double.parseDouble(v);
                case "--sensor-age-hours" -> sensorAgeHours = Long.parseLong(v);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        String user = System.getenv().getOrDefault("EMU_USERNAME", "patient01");
        String pass = System.getenv("EMU_PASSWORD");
        boolean generated = pass == null || pass.isBlank();
        if (generated) {
            byte[] b = new byte[9];
            new SecureRandom().nextBytes(b);
            pass = HexFormat.of().formatHex(b);
        }

        EmulatorConfig config = EmulatorConfig.defaults(seed, scenario).withFaults(dup, err);
        Instant now = Instant.now();
        SimClock clock = SimClock.scaled(now, speed);
        VirtualSensor sensor = new VirtualSensor(config, now.minus(Duration.ofHours(sensorAgeHours)));
        EmulatorServer server = new EmulatorServer(config, clock, sensor, user, pass);
        int actual = server.start(host, port);

        System.out.printf("Virtual CGM emulator on http://%s:%d  scenario=%s speed=%sx sensor=%s%n",
                host, actual, scenario, speed, sensor.serial());
        System.out.println("username: " + user + (generated ? "  password(generated): " + pass : "  password: (from EMU_PASSWORD)"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }
}
