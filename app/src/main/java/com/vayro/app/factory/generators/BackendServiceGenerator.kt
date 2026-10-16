package com.vayro.app.factory.generators

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.DataEntitySpec
import java.io.File

/**
 * Scaffolds standalone REST backend services in Node.js (Express) or Python (FastAPI) with SQLite database integration.
 */
object BackendServiceGenerator {

    enum class BackendFlavor {
        NODE_EXPRESS,
        FASTAPI_PYTHON
    }

    fun generateProject(targetDir: File, blueprint: AppBlueprint, flavor: BackendFlavor = BackendFlavor.NODE_EXPRESS): List<File> {
        val createdFiles = mutableListOf<File>()
        targetDir.mkdirs()

        when (flavor) {
            BackendFlavor.NODE_EXPRESS -> {
                createdFiles += writeFile(targetDir, "package.json", generateNodePackageJson(blueprint))
                createdFiles += writeFile(targetDir, "server.js", generateExpressServer(blueprint))
                createdFiles += writeFile(targetDir, "README.md", generateNodeReadme(blueprint))
            }
            BackendFlavor.FASTAPI_PYTHON -> {
                createdFiles += writeFile(targetDir, "requirements.txt", generatePythonRequirements())
                createdFiles += writeFile(targetDir, "main.py", generateFastApiMain(blueprint))
                createdFiles += writeFile(targetDir, "README.md", generatePythonReadme(blueprint))
            }
        }

        return createdFiles
    }

    private fun writeFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generateNodePackageJson(blueprint: AppBlueprint): String = """
        {
          "name": "${blueprint.name.lowercase().replace(Regex("[^a-z0-9]"), "-")}-backend",
          "version": "1.0.0",
          "description": "Backend API service for ${blueprint.name}",
          "main": "server.js",
          "scripts": {
            "start": "node server.js",
            "dev": "nodemon server.js"
          },
          "dependencies": {
            "express": "^4.19.2",
            "cors": "^2.8.5",
            "better-sqlite3": "^11.1.2",
            "morgan": "^1.10.0"
          },
          "devDependencies": {
            "nodemon": "^3.1.4"
          }
        }
    """

    private fun generateExpressServer(blueprint: AppBlueprint): String {
        val tableCreationSql = blueprint.entities.joinToString("\n") { entity ->
            val cols = entity.fields.joinToString(", ") { f ->
                val sqlType = when (f.type) {
                    "Int", "Long" -> "INTEGER"
                    "Double" -> "REAL"
                    "Boolean" -> "INTEGER"
                    else -> "TEXT"
                }
                val pk = if (f.primaryKey) " PRIMARY KEY" else ""
                "${f.name} $sqlType$pk"
            }
            "db.exec(`CREATE TABLE IF NOT EXISTS ${entity.tableName} ($cols);`);"
        }

        val crudRoutes = blueprint.entities.joinToString("\n\n") { entity ->
            val pkField = entity.fields.firstOrNull { it.primaryKey }?.name ?: "id"
            """
            // CRUD routes for ${entity.name}
            app.get('/api/${entity.tableName}', (req, res) => {
              const rows = db.prepare('SELECT * FROM ${entity.tableName}').all();
              res.json(rows);
            });

            app.get('/api/${entity.tableName}/:id', (req, res) => {
              const row = db.prepare('SELECT * FROM ${entity.tableName} WHERE $pkField = ?').get(req.params.id);
              if (!row) return res.status(404).json({ error: '${entity.name} not found' });
              res.json(row);
            });

            app.post('/api/${entity.tableName}', (req, res) => {
              const keys = Object.keys(req.body);
              if (keys.length === 0) return res.status(400).json({ error: 'Body required' });
              const placeholders = keys.map(() => '?').join(', ');
              const sql = `INSERT INTO ${entity.tableName} (` + keys.join(', ') + `) VALUES (` + placeholders + `)`;
              const stmt = db.prepare(sql);
              const result = stmt.run(...keys.map(k => req.body[k]));
              res.status(201).json({ success: true, id: result.lastInsertRowid });
            });

            app.delete('/api/${entity.tableName}/:id', (req, res) => {
              db.prepare('DELETE FROM ${entity.tableName} WHERE $pkField = ?').run(req.params.id);
              res.json({ success: true });
            });
            """.trimIndent()
        }

        return """
            const express = require('express');
            const cors = require('cors');
            const morgan = require('morgan');
            const Database = require('better-sqlite3');
            const path = require('path');

            const app = express();
            const PORT = process.env.PORT || 3000;

            app.use(cors());
            app.use(express.json());
            app.use(morgan('dev'));

            const db = new Database(path.join(__dirname, 'database.sqlite'));

            // Initialize Tables
            $tableCreationSql

            // Healthcheck
            app.get('/health', (req, res) => {
              res.json({ status: 'ok', app: '${blueprint.name}', timestamp: new Date() });
            });

            $crudRoutes

            app.listen(PORT, '0.0.0.0', () => {
              console.log(`🚀 ${blueprint.name} backend listening on port http://localhost:` + PORT);
            });
        """
    }

    private fun generateNodeReadme(blueprint: AppBlueprint): String = """
        # ${blueprint.name} - Backend API

        Node.js Express + SQLite backend for ${blueprint.name}.

        ## Quickstart
        ```bash
        npm install
        npm start
        ```
    """

    private fun generatePythonRequirements(): String = """
        fastapi==0.111.0
        uvicorn[standard]==0.30.1
        pydantic==2.7.4
        sqlite3
    """

    private fun generateFastApiMain(blueprint: AppBlueprint): String {
        return """
            import sqlite3
            from fastapi import FastAPI, HTTPException
            from pydantic import BaseModel
            from typing import List, Optional

            app = FastAPI(title="${blueprint.name} API", description="${blueprint.description}")

            def get_db():
                conn = sqlite3.connect("database.sqlite", check_same_thread=False)
                conn.row_factory = sqlite3.Row
                return conn

            @app.get("/health")
            def health_check():
                return {"status": "ok", "app": "${blueprint.name}"}
        """
    }

    private fun generatePythonReadme(blueprint: AppBlueprint): String = """
        # ${blueprint.name} - FastAPI Backend

        FastAPI + SQLite backend for ${blueprint.name}.

        ## Quickstart
        ```bash
        pip install -r requirements.txt
        uvicorn main:app --reload --port 8000
        ```
    """
}
