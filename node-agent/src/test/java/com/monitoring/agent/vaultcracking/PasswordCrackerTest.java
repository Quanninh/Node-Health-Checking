// package com.monitoring.agent.vaultcracking;

// import org.junit.jupiter.api.Test;

// import java.security.MessageDigest;
// import java.nio.charset.StandardCharsets;

// import static org.junit.jupiter.api.Assertions.*;

// public class PasswordCrackerTest {

//     private String computeSHA256(String input) {
//         try {
//             MessageDigest digest = MessageDigest.getInstance("SHA-256");
//             byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
//             StringBuilder hexString = new StringBuilder();
//             for (byte b : hash) {
//                 String hex = Integer.toHexString(0xff & b);
//                 if (hex.length() == 1) {
//                     hexString.append('0');
//                 }
//                 hexString.append(hex);
//             }
//             return hexString.toString();
//         } catch (Exception e) {
//             throw new RuntimeException(e);
//         }
//     }

//     @Test
//     public void testPasswordCrackerBasic() {
//         String password = "hello";
//         String hash = computeSHA256(password);

//         PasswordCracker cracker = new PasswordCracker(hash);
//         long totalPasswords = PasswordCracker.getTotalPossiblePasswords();

//         PasswordCracker.CrackResult result = cracker.crackRange(0, totalPasswords - 1);

//         assertTrue(result.found, "Should find the password");
//         assertEquals(password, result.password, "Should return correct password");
//     }

//     @Test
//     public void testPasswordCrackerWithRange() {
//         String password = "AAAAA";
//         String hash = computeSHA256(password);

//         PasswordCracker cracker = new PasswordCracker(hash);

//         PasswordCracker.CrackResult result = cracker.crackRange(0, 100_000_000);

//         assertTrue(result.found, "Should find the password in range");
//         assertEquals(password, result.password, "Should return correct password");
//     }

//     @Test
//     public void testPasswordCrackerTotalPasswords() {
//         long total = PasswordCracker.getTotalPossiblePasswords();
//         assertEquals(916_132_832L, total, "Should have 62^5 = 916132832 passwords");
//     }

//     @Test
//     public void testRangeDistributor() {
//         PasswordRangeDistributor distributor = new PasswordRangeDistributor();

//         PasswordRangeDistributor.PasswordRange range1 = distributor.assignRangeToNode("node1");
//         PasswordRangeDistributor.PasswordRange range2 = distributor.assignRangeToNode("node2");

//         assertNotNull(range1);
//         assertNotNull(range2);
//         assertNotEquals(range1.startIndex, range2.startIndex);
//         assertEquals("node1", distributor.getAssignedNodes().get(0));
//         assertEquals("node2", distributor.getAssignedNodes().get(1));
//     }
// }
