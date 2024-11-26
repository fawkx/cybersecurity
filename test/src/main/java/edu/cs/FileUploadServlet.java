package edu.cs;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

@WebServlet({"/FileUploadServlet"})
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024 * 10,  // Increased to 10MB
        maxFileSize = 1024 * 1024 * 50,       // Maximum file size of 50MB
        maxRequestSize = 1024 * 1024 * 100    // Maximum request size of 100MB
)
public class FileUploadServlet extends HttpServlet {
    private static final long serialVersionUID = 205242440643911308L;
    private static final String UPLOAD_DIR = "uploads";
    private Set<String> bannedIPs = new HashSet<>();
    private String dbUrl;
    private String dbUser;
    private String dbPassword;

    public void init() throws ServletException {
        super.init();
        String applicationPath = getServletContext().getRealPath("");
        loadBanList(applicationPath + File.separator + "WEB-INF" + File.separator + "banlist.txt");

        // Load database connection details from properties file
        String dbPropertiesPath = applicationPath + File.separator + "WEB-INF" + File.separator + "db.properties";
        Properties properties = new Properties();
        try (FileInputStream infile = new FileInputStream(dbPropertiesPath)) {
            properties.load(infile);
            dbUrl = properties.getProperty("db.url");
            dbUser = properties.getProperty("db.user");
            dbPassword = properties.getProperty("db.password");
        } catch (IOException e) {
            throw new ServletException("Error loading database properties: " + e.getMessage(), e);
        }

        // Register the MySQL JDBC driver
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ServletException("MySQL JDBC driver not found.", e);
        }
    }

    // Load banned IP addresses from a file
    private void loadBanList(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bannedIPs.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Handle POST requests (file uploads)
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String clientIP = request.getRemoteAddr();

        // Check if the client's IP address is banned
        if (bannedIPs.contains(clientIP)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("Access denied.");
            return;
        }

        String applicationPath = request.getServletContext().getRealPath("");
        String uploadFilePath = applicationPath + File.separator + UPLOAD_DIR;
        File fileSaveDir = new File(uploadFilePath);
        if (!fileSaveDir.exists()) {
            fileSaveDir.mkdirs();
        }

        String fileName = "";
        File uploadedFile = null;
        // Process uploaded file parts
        for (Part part : request.getParts()) {
            fileName = getFileName(part);
            fileName = fileName.substring(fileName.lastIndexOf("\\") + 1);  // Remove any path information
            uploadedFile = new File(uploadFilePath + File.separator + fileName);
            part.write(uploadedFile.getAbsolutePath());
        }

        // Check if the file was successfully uploaded
        if (uploadedFile == null || !uploadedFile.exists() || uploadedFile.length() == 0) {
            response.getWriter().write("File is empty or not found.");
            return;
        }

        // Connect to the database and insert file information
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String insertSQL = "INSERT INTO uploaded_files (file_name, upload_time, file_content) VALUES (?, ?, ?)";
            PreparedStatement statement = connection.prepareStatement(insertSQL);
            statement.setString(1, fileName);
            statement.setObject(2, LocalDateTime.now());

            // Read the uploaded file content
            try (FileInputStream fis = new FileInputStream(uploadedFile);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                System.out.println("Uploading file: " + uploadedFile.getAbsolutePath() + " with size: " + uploadedFile.length());
                statement.setBinaryStream(3, bis, (int) uploadedFile.length());
                statement.executeUpdate();

                // Create a base64-encoded version of the file for display
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = new FileInputStream(uploadedFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                }

                String base64File = Base64.getEncoder().encodeToString(baos.toByteArray());
                String mimeType = getServletContext().getMimeType(fileName);

                response.setContentType("text/html");
                PrintWriter out = response.getWriter();
                out.println("<html><body>");
                out.println("File uploaded and stored successfully.<br>");
                out.println("File name: " + fileName + "<br>");

                if (fileName.endsWith(".pdf")) {
                    // Extract text and images from PDF
                    try (PDDocument document = PDDocument.load(uploadedFile)) {
                        PDFTextStripper pdfStripper = new PDFTextStripper();
                        String pdfText = pdfStripper.getText(document);
                        String escapedPdfText = StringEscapeUtils.escapeHtml4(pdfText);
                        out.println("<pre>" + escapedPdfText + "</pre>");

                        PDFRenderer pdfRenderer = new PDFRenderer(document);
                        for (int page = 0; page < document.getNumberOfPages(); ++page) {
                            BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 100);  // Render at lower DPI
                            BufferedImage resizedImage = resizeImage(bim, bim.getWidth() / 2, bim.getHeight() / 2);  // Resize image
                            ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
                            ImageIOUtil.writeImage(resizedImage, "png", imageBaos, 300);
                            String base64Image = Base64.getEncoder().encodeToString(imageBaos.toByteArray());
                            out.println("<img src='data:image/png;base64," + base64Image + "' /><br>");
                        }
                    }
                } else if (mimeType != null && mimeType.startsWith("image/")) {
                    // Display image
                    out.println("<img src='data:" + mimeType + ";base64," + base64File + "' alt='" + fileName + "' />");
                } else {
                    // Read and escape text content
                    String fileContent = new String(baos.toByteArray(), "UTF-8");
                    String escapedContent = StringEscapeUtils.escapeHtml4(fileContent);
                    out.println("<pre>" + escapedContent + "</pre>");
                }

                out.println("</body></html>");
            } catch (IOException e) {
                System.err.println("Error reading file content: " + e.getMessage());
                e.printStackTrace();
                response.getWriter().write("Error reading file content: " + e.getMessage());
                return;
            }
        } catch (SQLException e) {
            System.err.println("Error saving file info to database: " + e.getMessage());
            e.printStackTrace();
            response.getWriter().write("Error saving file info to database: " + e.getMessage());
        }
    }

    // Resize the image to reduce memory usage
    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        graphics2D.dispose();
        return resizedImage;
    }

    // Extract the file name from the part header
    private String getFileName(Part part) {
        String contentDisp = part.getHeader("content-disposition");
        System.out.println("content-disposition header= " + contentDisp);
        String[] tokens = contentDisp.split(";");
        for (String token : tokens) {
            if (token.trim().startsWith("filename")) {
                return token.substring(token.indexOf("=") + 2, token.length() - 1);
            }
        }
        return "";
    }
}
