import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class GameServer implements AutoCloseable {
    // จำนวนช่องผู้เล่น/เลนที่ Server รองรับในหนึ่งห้อง
    static final int MAX_PLAYERS = 3;
    // ระยะทางสูงสุดของผู้เล่น เมื่อถึงค่านี้ถือว่าเข้าเส้นชัย
    private static final int MAX_PROGRESS = 100;

    // ใช้ล็อกข้อมูลการแข่งขันร่วมกัน เพราะแต่ละ Client ถูกจัดการด้วยคนละ Thread
    private final Object stateLock = new Object();
    // ตำแหน่งในอาร์เรย์เป็นหมายเลขเลน และ null หมายถึงเลนนั้นยังว่าง
    private final Player[] players = new Player[MAX_PLAYERS];
    // Socket ที่รับการเชื่อมต่อจาก Client ใหม่
    private final ServerSocket serverSocket;
    // เป็น false เมื่อปิด Server เพื่อให้ลูปรับการเชื่อมต่อหยุดทำงาน
    private volatile boolean running = true;
    // หมายเลขเลนของผู้ชนะ; -1 หมายถึงยังไม่มีผู้ชนะ
    private int winnerLane = -1;

    public GameServer(int port) throws IOException {
        // ผูก ServerSocket กับ Port ที่ผู้ใช้เลือก หากเปิดไม่ได้จะส่ง IOException กลับไป
        serverSocket = new ServerSocket(port);
        // แยกการรอ Client ออกจาก UI และตั้งเป็น daemon เพื่อไม่ขวางการปิดโปรแกรม
        Thread acceptThread = new Thread(this::acceptConnections, "speedrun-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        // คืน Port ที่ Server ใช้อยู่จริง (รองรับกรณีเปิดด้วย Port 0 เพื่อสุ่ม Port)
        return serverSocket.getLocalPort();
    }

    private void acceptConnections() {
        // รอรับ Client ต่อเนื่องจนกว่าจะมีการเรียก close()
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // ให้แต่ละ Client มี Thread แยกกัน เพื่อให้การรอข้อความจาก Client
                // คนหนึ่งไม่ขัดขวางการรับหรือประมวลผลข้อความจากคนอื่น
                Thread clientThread = new Thread(() -> handleClient(socket), "speedrun-server-client");
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException exception) {
                // การปิด Server จะทำให้ accept() โยน IOException เป็นวิธีปลุก Thread ที่กำลังรอ
                // จึงรายงานข้อผิดพลาดเฉพาะกรณีที่ Server ยังควรทำงานอยู่
                if (running) {
                    System.err.println("Could not accept game client: " + exception.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        // เก็บ Player ไว้เพื่อถอดออกจากห้องใน finally เมื่อ Client หลุดหรือส่งข้อมูลผิด
        Player player = null;
        // try-with-resources ปิด Socket และ stream เมื่อจบการจัดการ Client ทุกกรณี
        try (Socket clientSocket = socket;
                BufferedReader input = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(
                        new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            clientSocket.setTcpNoDelay(true);

            // ข้อความแรกจาก Client ต้องเป็น JOIN| ตามด้วยชื่อที่เข้ารหัสแบบ Base64 URL-safe
            String joinMessage = input.readLine();
            if (joinMessage == null || !joinMessage.startsWith("JOIN|")) {
                output.println("ERROR|ส่งข้อมูลเข้าร่วมไม่ถูกต้อง");
                return;
            }

            // ถอดรหัสและตรวจสอบชื่อก่อนเพิ่มผู้เล่นเข้าห้อง
            String name = decodeName(joinMessage.substring("JOIN|".length()));
            synchronized (stateLock) {
                // เลือกเลนว่างภายใต้ lock เพื่อไม่ให้ Client สองคนได้เลนเดียวกัน
                int lane = findAvailableLane();
                if (lane < 0) {
                    output.println("ERROR|ห้องเต็มแล้ว (รองรับสูงสุด 3 คน)");
                    return;
                }
                // บันทึกผู้เล่นลงสถานะกลาง แจ้งเลนที่ได้รับ แล้วกระจายสถานะล่าสุดให้ทุกคน
                player = new Player(lane, name, output);
                players[lane] = player;
                output.println("WELCOME|" + lane);
                broadcastState();
            }

            // รับคำสั่งจาก Client ตลอดเวลาที่การเชื่อมต่อยังเปิดอยู่
            // STEP เพิ่มความคืบหน้าของผู้เล่น ส่วน RESET เริ่มการแข่งขันใหม่ให้ทั้งห้อง
            String message;
            while ((message = input.readLine()) != null && running) {
                synchronized (stateLock) {
                    // หาก Player นี้ถูกถอดออกจากเลนไปแล้ว ก็ไม่ประมวลผลคำสั่งต่อ
                    if (players[player.lane] != player) {
                        break;
                    }
                    if ("STEP".equals(message)) {
                        advance(player);
                        broadcastState();
                    } else if ("RESET".equals(message)) {
                        resetRace();
                        broadcastState();
                    }
                }
            }
        } catch (IOException exception) {
            // การอ่าน/เขียนล้มเหลวมักหมายถึง Client ปิดการเชื่อมต่อหรือเครือข่ายขาด
            if (running) {
                System.err.println("Game client disconnected: " + exception.getMessage());
            }
        } finally {
            // ปลดเลนเมื่อ Client ออกจากห้อง และเผยแพร่สถานะใหม่ให้ผู้เล่นที่ยังเหลือ
            if (player != null) {
                synchronized (stateLock) {
                    if (players[player.lane] == player) {
                        players[player.lane] = null;
                        if (winnerLane == player.lane) {
                            winnerLane = -1;
                        }
                        broadcastState();
                    }
                }
            }
        }
    }

    private static String decodeName(String encoded) throws IOException {
        try {
            // ถอด Base64 URL-safe กลับเป็น UTF-8 แล้วตัดช่องว่างหัวท้าย
            String name = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
            // จำกัดชื่อให้อยู่ในรูปแบบที่ส่งกลับในโปรโตคอลได้อย่างปลอดภัยและอ่านได้
            if (name.isEmpty() || name.length() > 20 || name.indexOf('|') >= 0) {
                throw new IOException("ชื่อผู้เล่นต้องมี 1 ถึง 20 ตัวอักษร");
            }
            return name;
        } catch (IllegalArgumentException exception) {
            // Base64 ที่ผิดรูปแบบจะแปลงเป็น IOException เพื่อจัดการเหมือนข้อมูล JOIN ที่ผิด
            throw new IOException("ชื่อผู้เล่นไม่ถูกต้อง", exception);
        }
    }

    private int findAvailableLane() {
        // ค้นหาเลนว่างจากหมายเลขน้อยไปมาก เพื่อเลือกเลนแรกที่พร้อมใช้งาน
        for (int lane = 0; lane < players.length; lane++) {
            if (players[lane] == null) {
                return lane;
            }
        }
        // ไม่มีสมาชิก null แปลว่าทุกเลนมีผู้เล่นครบแล้ว
        return -1;
    }

    private void advance(Player player) {
        // หลังมีผู้ชนะแล้ว หรือผู้เล่นถึงเส้นชัยแล้ว จะไม่เพิ่มความคืบหน้าอีก
        if (winnerLane >= 0 || player.progress >= MAX_PROGRESS) {
            return;
        }
        // แต่ละคำสั่ง STEP เพิ่มระยะทาง 2 หน่วย และจำกัดไม่ให้เกินเส้นชัย
        player.progress = Math.min(player.progress + 2, MAX_PROGRESS);
        if (player.progress == MAX_PROGRESS) {
            // ผู้เล่นคนแรกที่แตะระยะสูงสุดจะเป็นผู้ชนะของรอบนี้
            winnerLane = player.lane;
        }
    }

    private void resetRace() {
        // ล้างข้อมูลผู้ชนะและเริ่มระยะทางของผู้เล่นที่ยังเชื่อมต่ออยู่ใหม่
        winnerLane = -1;
        for (Player player : players) {
            if (player != null) {
                player.progress = 0;
            }
        }
    }

    private void broadcastState() {
        // สร้างข้อความสถานะตามรูปแบบ STATE|เลนผู้ชนะ|ข้อมูลผู้เล่น
        // ข้อมูลผู้เล่นแต่ละคนคั่นด้วย ; และประกอบด้วย เลน,ชื่อที่เข้ารหัส,ความคืบหน้า
        StringBuilder message = new StringBuilder("STATE|").append(winnerLane).append('|');
        boolean first = true;
        for (Player player : players) {
            if (player != null) {
                if (!first) {
                    message.append(';');
                }
                first = false;
                message.append(player.lane).append(',')
                        .append(encodeName(player.name)).append(',')
                        .append(player.progress);
            }
        }
        String state = message.toString();
        // รวบรวม Client ที่ส่งข้อความไม่สำเร็จไว้ก่อน เพื่อไม่แก้ players ระหว่างวนส่ง
        List<Player> failed = new ArrayList<>();
        for (Player player : players) {
            if (player != null && !player.send(state)) {
                failed.add(player);
            }
        }
        // ถอด Client ที่เขียนข้อมูลไม่สำเร็จออกจากห้อง
        for (Player player : failed) {
            players[player.lane] = null;
        }
    }

    private static String encodeName(String name) {
        // เข้ารหัสชื่อก่อนใส่ในข้อความคั่นด้วย | , และ ; เพื่อไม่ให้ตัวอักษรชื่อรบกวนรูปแบบ
        return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        // แจ้งลูปรับการเชื่อมต่อว่าต้องหยุด แล้วปิด ServerSocket เพื่อปลุก accept() ที่กำลังรอ
        running = false;
        try {
            serverSocket.close();
        } catch (IOException exception) {
            System.err.println("Could not close game server: " + exception.getMessage());
        }
        // ปิดช่องทางส่งข้อมูลของ Client ทุกคนภายใต้ lock เดียวกับที่ใช้แก้สถานะห้อง
        synchronized (stateLock) {
            for (Player player : players) {
                if (player != null) {
                    player.close();
                }
            }
        }
    }

    private static final class Player {
        // ข้อมูลผู้เล่นที่ Server ต้องใช้ระหว่างการแข่งขัน
        private final int lane;
        private final String name;
        private final PrintWriter output;
        private int progress;

        Player(int lane, String name, PrintWriter output) {
            this.lane = lane;
            this.name = name;
            this.output = output;
        }

        synchronized boolean send(String message) {
            // ส่งหนึ่งบรรทัดให้ Client; auto-flush เปิดไว้ตอนสร้าง PrintWriter
            output.println(message);
            // PrintWriter ไม่โยน IOException โดยตรง จึงต้องตรวจ error flag เอง
            return !output.checkError();
        }

        synchronized void close() {
            // ปิด writer เพื่อยุติการส่งข้อมูลไปยัง Client
            output.close();
        }
    }
}
