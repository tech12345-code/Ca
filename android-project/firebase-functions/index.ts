import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as admin from "firebase-admin";

admin.initializeApp();

const db = admin.firestore();
const messaging = admin.messaging();

/**
 * Trusted server-side trigger: sends non-sensitive budget alert push payloads
 * when a transaction crosses 50%, 75%, 90%, or 100% of monthly budget,
 * deduplicated via /users/{uid}/alertHistory/{monthKey}_{threshold}.
 */
export const onTransactionCreatedCheckBudget = onDocumentCreated(
  "users/{userId}/transactions/{transactionId}",
  async (event) => {
    const userId = event.params.userId;
    const tx = event.data?.data();
    if (!tx || tx.type !== "EXPENSE") return;

    const userSnap = await db.collection("users").doc(userId).get();
    const user = userSnap.data();
    if (!user || !user.notifBudgetAlerts || !user.monthlyBudget || user.monthlyBudget <= 0) {
      return;
    }

    const monthKey = String(tx.transactionDate).slice(0, 7); // YYYY-MM
    const txsSnap = await db
      .collection("users")
      .doc(userId)
      .collection("transactions")
      .where("type", "==", "EXPENSE")
      .get();

    let monthlySpent = 0;
    txsSnap.forEach((doc) => {
      const d = doc.data();
      if (String(d.transactionDate).startsWith(monthKey)) {
        monthlySpent += Number(d.amount || 0);
      }
    });

    const pct = (monthlySpent / Number(user.monthlyBudget)) * 100;
    const thresholds = [50, 75, 90, 100];

    for (const threshold of thresholds) {
      if (pct >= threshold) {
        const alertKey = `${monthKey}_${threshold}`;
        const alertRef = db.collection("users").doc(userId).collection("alertHistory").doc(alertKey);
        const existing = await alertRef.get();
        if (!existing.exists) {
          await alertRef.set({
            alertKey,
            uid: userId,
            monthKey,
            thresholdPercent: threshold,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
          });
          await sendToUserTokens(userId, {
            type: "BUDGET_ALERT",
            threshold: String(threshold),
            summary: `Monthly budget threshold ${threshold}% reached.`,
          });
        }
      }
    }
  }
);

/**
 * Scheduled Daily Expense Reminder & Summary dispatcher.
 */
export const dispatchDailySummary = onSchedule("every day 20:30", async () => {
  const usersSnap = await db.collection("users").where("notifDailySummary", "==", true).get();
  for (const userDoc of usersSnap.docs) {
    await sendToUserTokens(userDoc.id, {
      type: "DAILY_SUMMARY",
      summary: "Open Smart Expense Manager to view today's spending and remaining budget.",
    });
  }
});

/**
 * Scheduled Weekly Financial Report dispatcher.
 */
export const dispatchWeeklyReport = onSchedule("every sunday 19:00", async () => {
  const usersSnap = await db.collection("users").where("notifWeeklyReport", "==", true).get();
  for (const userDoc of usersSnap.docs) {
    await sendToUserTokens(userDoc.id, {
      type: "WEEKLY_REPORT",
      summary: "Your weekly income, expense, and top category report is ready.",
    });
  }
});

async function sendToUserTokens(userId: string, data: Record<string, string>) {
  const tokensSnap = await db.collection("users").doc(userId).collection("fcmTokens").get();
  const tokens = tokensSnap.docs.map((d) => String(d.data().token)).filter(Boolean);
  if (tokens.length === 0) return;

  // Data-only payload avoids exposing sensitive numbers on lockscreens
  await messaging.sendEachForMulticast({
    tokens,
    data,
    android: {
      priority: "high",
    },
  });
}
