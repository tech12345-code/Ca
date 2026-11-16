import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GoogleGenAI, Type } from "@google/genai";
import dotenv from "dotenv";

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PORT = Number(process.env.PORT || 3000);

const app = express();
app.use(express.json({ limit: "16mb" }));

app.post("/api/receipt-ocr", async (req, res) => {
  try {
    const { imageBase64, mimeType, rawText } = req.body as {
      imageBase64?: string;
      mimeType?: string;
      rawText?: string;
    };

    if (!imageBase64 && (!rawText || !rawText.trim())) {
      res.status(400).json({ error: "Provide a receipt image, PDF, or receipt text." });
      return;
    }

    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      res.status(500).json({ error: "GEMINI_API_KEY is not configured on the server." });
      return;
    }

    const ai = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          "User-Agent": "aistudio-build",
        },
      },
    });

    const parts: Array<{ text: string } | { inlineData: { mimeType: string; data: string } }> = [];

    if (imageBase64 && mimeType) {
      parts.push({
        inlineData: {
          mimeType,
          data: imageBase64,
        },
      });
    }

    const instructionText = [
      "Extract structured expense details from this receipt, invoice, digital UPI payment screenshot, or SMS/bill text.",
      "CRITICAL RULES:",
      "1. NEVER invent, guess, or fabricate values that are missing or illegible. Return empty string \"\" for any unclear/missing field.",
      "2. For transactionDate, format strictly as YYYY-MM-DD if clearly visible, otherwise \"\".",
      "3. For transactionTime, format strictly as 24-hour HH:mm if clearly visible, otherwise \"\".",
      "4. For amount, return plain numeric string like \"425.50\" without currency symbol if clearly visible, otherwise \"\".",
      "5. For suggestedCategory, pick the closest from: Food, Travel, Groceries, Shopping, Education, Bills, Entertainment, Health, Other.",
      rawText ? `\nReceipt / Payment Text:\n${rawText}` : "",
    ].join("\n");

    parts.push({ text: instructionText });

    const response = await ai.models.generateContent({
      model: "gemini-4-hydrogen",
      contents: { parts },
      config: {
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.OBJECT,
          properties: {
            merchantName: { type: Type.STRING },
            amount: { type: Type.STRING },
            transactionDate: { type: Type.STRING },
            transactionTime: { type: Type.STRING },
            paymentMethod: { type: Type.STRING },
            referenceId: { type: Type.STRING },
            invoiceNumber: { type: Type.STRING },
            suggestedCategory: { type: Type.STRING },
            rawText: { type: Type.STRING },
          },
          required: [
            "merchantName",
            "amount",
            "transactionDate",
            "transactionTime",
            "paymentMethod",
            "referenceId",
            "invoiceNumber",
            "suggestedCategory",
            "rawText",
          ],
        },
      },
    });

    const jsonText = (response.text || "{}").trim();
    const parsed = JSON.parse(jsonText);
    res.json(parsed);
  } catch (err) {
    console.error("Receipt OCR error:", err);
    res.status(500).json({
      error: err instanceof Error ? err.message : "Failed to extract receipt fields",
    });
  }
});

async function startServer() {
  if (process.env.NODE_ENV !== "production") {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.join(__dirname, "dist");
    app.use(express.static(distDir));
    app.get("/{*splat}", (_req, res) => {
      res.sendFile(path.join(distDir, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Smart Expense Manager server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer();
