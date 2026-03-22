import { drizzle } from "drizzle-orm/postgres-js";
import { desc, eq, inArray } from "drizzle-orm";
import postgres from "postgres";
import { genSaltSync, hashSync } from "bcrypt-ts";
import { chat, chunk, user } from "@/schema";

// Optionally, if not using email/pass login, you can
// use the Drizzle adapter for Auth.js / NextAuth
// https://authjs.dev/reference/adapter/drizzle
let client: ReturnType<typeof postgres> | null = null;
let db: ReturnType<typeof drizzle> | null = null;

function getDb() {
  if (!db) {
    const connectionString = process.env.POSTGRES_URL;
    if (!connectionString) {
      throw new Error('POSTGRES_URL environment variable is not set');
    }
    client = postgres(`${connectionString}?sslmode=require`);
    db = drizzle(client);
  }
  return db;
}

export async function getUser(email: string) {
  return await getDb().select().from(user).where(eq(user.email, email));
}

export async function createUser(email: string, password: string) {
  let salt = genSaltSync(10);
  let hash = hashSync(password, salt);

  return await getDb().insert(user).values({ email, password: hash });
}

export async function createMessage({
  id,
  messages,
  author,
}: {
  id: string;
  messages: any;
  author: string;
}) {
  const selectedChats = await getDb().select().from(chat).where(eq(chat.id, id));

  if (selectedChats.length > 0) {
    return await getDb()
      .update(chat)
      .set({
        messages: JSON.stringify(messages),
      })
      .where(eq(chat.id, id));
  }

  return await getDb().insert(chat).values({
    id,
    createdAt: new Date(),
    messages: JSON.stringify(messages),
    author,
  });
}

export async function getChatsByUser({ email }: { email: string }) {
  return await getDb()
    .select()
    .from(chat)
    .where(eq(chat.author, email))
    .orderBy(desc(chat.createdAt));
}

export async function getChatById({ id }: { id: string }) {
  const [selectedChat] = await getDb().select().from(chat).where(eq(chat.id, id));
  return selectedChat;
}

export async function insertChunks({ chunks }: { chunks: any[] }) {
  return await getDb().insert(chunk).values(chunks);
}

export async function getChunksByFilePaths({
  filePaths,
}: {
  filePaths: Array<string>;
}) {
  return await getDb()
    .select()
    .from(chunk)
    .where(inArray(chunk.filePath, filePaths));
}

export async function deleteChunksByFilePath({
  filePath,
}: {
  filePath: string;
}) {
  return await getDb().delete(chunk).where(eq(chunk.filePath, filePath));
}
