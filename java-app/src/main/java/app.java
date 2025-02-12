import static spark.Spark.*;
import java.io.IOException;

public class app {
    public static void main(String[] args) throws IOException {
        int port = 4567;
        port(port);

        System.out.println("Plesae navigate to http://localhost:"+port+"/hello?name=IAST");
        // Route that reflects user input without proper encoding, introducing a potential XSS vulnerability
        get("/hello", (req, res) -> {
            String name = req.queryParams("name");
            return "<p>Hello, " + name + "!</p>";
        });


    }
}
