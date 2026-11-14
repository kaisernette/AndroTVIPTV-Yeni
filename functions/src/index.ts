import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();
const REGION = "us-central1";
const DEVICE_LIMIT = 3;

function requireUid(request: { auth?: { uid: string } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Bu işlem için giriş yapmalısınız.");
  return uid;
}

function validDeviceId(value: unknown): value is string {
  return typeof value === "string" && /^[a-f0-9-]{20,64}$/i.test(value);
}

export const registerDevice = onCall({ region: REGION, enforceAppCheck: true }, async (request) => {
  const uid = requireUid(request);
  const deviceId = request.data?.deviceId;
  const deviceName = typeof request.data?.deviceName === "string" ? request.data.deviceName.slice(0, 80) : "Android cihaz";
  const deviceType = typeof request.data?.deviceType === "string" ? request.data.deviceType.slice(0, 24) : "android";
  if (!validDeviceId(deviceId)) throw new HttpsError("invalid-argument", "Geçersiz cihaz kimliği.");

  const userRef = db.collection("users").doc(uid);
  const deviceRef = userRef.collection("devices").doc(deviceId);

  const result = await db.runTransaction(async (transaction) => {
    const deviceSnapshot = await transaction.get(deviceRef);
    const devicesSnapshot = await transaction.get(userRef.collection("devices"));
    if (!deviceSnapshot.exists && devicesSnapshot.size >= DEVICE_LIMIT) {
      throw new HttpsError("resource-exhausted", "Hesabınız en fazla 3 cihazda kullanılabilir. Önce bir cihazı kaldırın.");
    }

    transaction.set(deviceRef, {
      deviceId,
      deviceName,
      deviceType,
      createdAt: deviceSnapshot.exists ? deviceSnapshot.get("createdAt") : FieldValue.serverTimestamp(),
      lastSeenAt: FieldValue.serverTimestamp(),
      platform: "android"
    }, { merge: true });

    transaction.set(userRef, {
      email: request.auth?.token.email ?? null,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });

    return { activeCount: devicesSnapshot.size + (deviceSnapshot.exists ? 0 : 1), deviceLimit: DEVICE_LIMIT };
  });

  return result;
});

export const removeDevice = onCall({ region: REGION, enforceAppCheck: true }, async (request) => {
  const uid = requireUid(request);
  const deviceId = request.data?.deviceId;
  if (!validDeviceId(deviceId)) throw new HttpsError("invalid-argument", "Geçersiz cihaz kimliği.");

  const deviceRef = db.collection("users").doc(uid).collection("devices").doc(deviceId);
  await deviceRef.delete();
  return { removed: true };
});
