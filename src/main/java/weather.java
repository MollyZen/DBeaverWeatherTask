import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class weather extends HttpServlet {
    
    private static final ReentrantLock lock = new ReentrantLock();
    private static final Condition updateCompleted = lock.newCondition();
    
    final String url = "jdbc:mysql://localhost:3306/weather";
    final String user = "root";
    final String password = "root";
    final String queryPreset =
            "SELECT weather_value FROM weather_history WHERE weather_date=CURDATE()";
    
    protected enum Scale{
        CEL, FAR, KEL
    }
    
    protected int adjustCelForScale(int value, Scale desiredScale){
        return switch (desiredScale){
            case FAR -> Math.round((float) (value*1.8+32.));
            case KEL -> Math.round((float) (value + 273.15));
            default -> value;
        };
    }
    
    protected String formXML(int value, Scale scale){
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            //check if data retrieved is correct
            if (value == Integer.MAX_VALUE){
                Element error = doc.createElement("error");
                error.appendChild(doc.createTextNode("Can't retrieve data"));
                doc.appendChild(error);
            }
            else{
                Element cur_weather = doc.createElement("cur_weather");
                cur_weather.appendChild(doc.createTextNode(String.format("%+d", adjustCelForScale(value, scale))));
                cur_weather.setAttribute("scale", scale.name());
                doc.appendChild(cur_weather);
            }
            StringWriter sw = new StringWriter();
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (ParserConfigurationException |TransformerException e) {
            Logger.getLogger(weather.class.getName()).log(Level.SEVERE, null, e);
            return "";
        }
    }
    
    protected String formJSON(int value, Scale scale){
        JSONObject json = new JSONObject();
        //check if data retrieved is correct
        if (value == Integer.MAX_VALUE){
            json.put("error", "Can't retrieve data");
        }
        else{
            json.put("cur_weather", String.format("%+d", adjustCelForScale(value, scale)));
            json.put("scale", scale.name());
        }
        return json.toString(1);
    }
    
    protected int databaseRequest(){
        //getting weather data from DB
        try (Connection con = DriverManager.getConnection(url, user, password)){
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery(queryPreset);
            if(!rs.next()){
                return Integer.MAX_VALUE;
            }
            else{
                return rs.getInt(1);
            }
        }catch(SQLException e){
            Logger.getLogger(weather.class.getName()).log(Level.SEVERE, null, e);
            return Integer.MAX_VALUE;
        }
    }
    
    protected void databaseUpdate(int value){
        //updating DB with new temp for current day
        if (value == Integer.MAX_VALUE) return;
        try (Connection con = DriverManager.getConnection(url, user, password)){
            //getting data from DB
            con.createStatement()
                    .executeUpdate(
                            "INSERT INTO weather_history(weather_date, weather_value) VALUES(CURDATE(),%d);"
                                    .formatted(value));
        }catch(SQLException e){
            Logger.getLogger(weather.class.getName()).log(Level.SEVERE, null, e);
        }
    }
    
    protected int getDataFromYandex(){
        StringBuilder sb;
        try {
            URL yandexURL;
            yandexURL = new URL("https://yandex.ru/");
            sb = new StringBuilder();
            BufferedReader br;
            br = new BufferedReader(new InputStreamReader(yandexURL.openStream()));
            char[] buff = new char[1024];
            while (br.read(buff) != -1){
                sb.append(buff);
            }
        } catch (IOException ex) {
            Logger.getLogger(weather.class.getName()).log(Level.SEVERE, null, ex);
            return Integer.MAX_VALUE;
        }
        String html = sb.toString();
        int degPos = html.indexOf("°");
        int signPos = degPos - 2;
        while (html.charAt(signPos) != '+' && html.charAt(signPos) != '-'){
            --signPos;
        }
        return Integer.parseInt(html.substring(signPos, degPos - 1));
    }
    /**
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code>
     * methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        int value = 0;
        //getting data from DB
        int rs = databaseRequest();
        //if no data - check yandex and update DB
        if(rs == Integer.MAX_VALUE){
            //making sure only one thread is able to modify DB
            if (lock.tryLock()){
                value = getDataFromYandex();
                databaseUpdate(value);
                lock.unlock();
            }
            else{
                //if data is already being retrieved we wait for this action to end
                //and request new data from DBs
                try {
                    updateCompleted.await();
                } catch (InterruptedException ex) {
                    Logger.getLogger(weather.class.getName()).log(Level.SEVERE, null, ex);
                    return;
                }
                value = databaseRequest();
            }
        }
        else{
            value = rs;
        }
        String type = request.getParameter("type") != null ? request.getParameter("type").toUpperCase() : "JSON";
        String scaleStr = request.getParameter("scale") != null ? request.getParameter("scale").toUpperCase() : "";
        Scale scaleEnum = switch (scaleStr){
            case "CEL" -> Scale.CEL;
            case "FAR" -> Scale.FAR;
            case "KEL" -> Scale.KEL;
            default -> Scale.CEL;
        };
        //supports JSON and XML, former one is default
        String result;
        if (type.equals("XML")){
            result = formXML(value, scaleEnum);
        }
        else{
            result = formJSON(value, scaleEnum);
        }
        response.setContentType("application/"+type.toLowerCase()+";charset=UTF-8");
        
        PrintWriter out = response.getWriter();
        out.print(result);
        out.flush();
    }
    
    // <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }
    
    /**
     * Handles the HTTP <code>POST</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }
    
    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Returns current weather";
    }// </editor-fold>
    
}
