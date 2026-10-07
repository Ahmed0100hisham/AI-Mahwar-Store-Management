package com.almahwar.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import java.sql.Connection;
import java.sql.SQLException;

/** Only unique disposable API catalogs. The application under test owns schema initialization. */
public final class TemporaryApiDatabase {
    public static final String NAME="AlMahwarApiSessionIT_"+java.util.UUID.randomUUID().toString().replace("-","");
    private TemporaryApiDatabase() { }
    public static void create() throws SQLException {
        try (var con=TemporaryDatabase.connect("master"); var st=con.createStatement()) {
            st.execute("CREATE DATABASE ["+NAME+"]");
        }
    }
    public static void drop() throws SQLException {
        try (var con=TemporaryDatabase.connect("master"); var st=con.createStatement()) {
            st.execute("IF DB_ID(N'"+NAME+"') IS NOT NULL BEGIN ALTER DATABASE ["+NAME+"] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ["+NAME+"]; END");
        }
    }
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.profiles.active",()->"dev"); // disposable SQL Server has a self-signed certificate
        registry.add("almahwar.api.db.host",TemporaryDatabase::host);
        registry.add("almahwar.api.db.port",TemporaryDatabase::port);
        registry.add("almahwar.api.db.name",()->NAME);
        registry.add("almahwar.api.db.user",TemporaryDatabase::user);
        registry.add("almahwar.api.db.password",TemporaryDatabase::password);
        registry.add("almahwar.api.db.trust-server-certificate",()->String.valueOf(TemporaryDatabase.trustServerCertificate()));
        registry.add("almahwar.api.db.initialize",()->"true");
    }
    public static String[] serverArgs() {
        return new String[]{"--spring.profiles.active=dev","--almahwar.api.db.host="+TemporaryDatabase.host(),
                "--almahwar.api.db.port="+TemporaryDatabase.port(),"--almahwar.api.db.name="+NAME,
                "--almahwar.api.db.user="+TemporaryDatabase.user(),"--almahwar.api.db.password="+TemporaryDatabase.password(),
                "--almahwar.api.db.trust-server-certificate="+TemporaryDatabase.trustServerCertificate()};
    }
    public static Object query(String sql,Object... params) throws SQLException {
        try (var con=TemporaryDatabase.connect(NAME);var ps=con.prepareStatement(sql)) {
            for(int i=0;i<params.length;i++) ps.setObject(i+1,params[i]);
            try(var rs=ps.executeQuery()) { return rs.next()?rs.getObject(1):null; }
        }
    }
    public static void exec(String sql,Object... params) throws SQLException {
        try(var con=TemporaryDatabase.connect(NAME);var ps=con.prepareStatement(sql)) {
            for(int i=0;i<params.length;i++) ps.setObject(i+1,params[i]);
            ps.execute();
        }
    }
    public static String[] withSessionArgs(String... args) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(args),java.util.Arrays.stream(serverArgs()))
                .toArray(String[]::new);
    }
}
