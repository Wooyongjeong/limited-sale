package db.nickmigration;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V3__member_nickname extends BaseJavaMigration {
    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz가나다라마";

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE members ADD nickname VARCHAR(12) NULL, "
                + "ADD nickname_key VARCHAR(12) COLLATE utf8mb4_bin NULL");
            try (ResultSet rows = statement.executeQuery("SELECT id FROM members")) {
                try (PreparedStatement update = context.getConnection().prepareStatement(
                        "UPDATE members SET nickname = ?, nickname_key = ? WHERE id = ?")) {
                    while (rows.next()) {
                        String nickname = encode(rows.getLong(1));
                        update.setString(1, nickname);
                        update.setString(2, nickname);
                        update.setLong(3, rows.getLong(1));
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
            statement.execute("ALTER TABLE members MODIFY nickname VARCHAR(12) NOT NULL, "
                + "MODIFY nickname_key VARCHAR(12) COLLATE utf8mb4_bin NOT NULL, "
                + "ADD CONSTRAINT uk_members_nickname UNIQUE (nickname_key)");
        }
    }

    private static String encode(long id) {
        BigInteger value = BigInteger.valueOf(id).subtract(BigInteger.valueOf(Long.MIN_VALUE));
        BigInteger radix = BigInteger.valueOf(DIGITS.length());
        StringBuilder result = new StringBuilder();
        do {
            BigInteger[] division = value.divideAndRemainder(radix);
            result.append(DIGITS.charAt(division[1].intValue()));
            value = division[0];
        } while (value.signum() > 0);
        return result.reverse().toString();
    }
}
