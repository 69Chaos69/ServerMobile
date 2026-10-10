package com.example.servermobile

object FrontendUI {
    val HTML_CONTENT = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Server Mobile</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-color: #0f172a;
            --glass-bg: rgba(30, 41, 59, 0.7);
            --glass-border: rgba(255, 255, 255, 0.1);
            --text-color: #f8fafc;
            --accent: #3b82f6;
            --accent-hover: #2563eb;
        }
        body {
            margin: 0; padding: 0; font-family: 'Inter', sans-serif;
            background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 100%);
            color: var(--text-color); min-height: 100vh;
            display: flex; flex-direction: column; align-items: center;
        }
        .container {
            width: 90%; max-width: 1000px; margin-top: 50px;
        }
        .header {
            display: flex; justify-content: space-between; align-items: center; margin-bottom: 30px;
        }
        h1 { font-weight: 600; margin: 0; letter-spacing: 1px; }
        
        .view-controls {
            display: flex; gap: 10px;
        }
        .icon-btn {
            background: rgba(255, 255, 255, 0.1);
            border: 1px solid var(--glass-border);
            color: var(--text-color);
            padding: 8px 12px;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            display: flex; align-items: center; justify-content: center;
        }
        .icon-btn:hover {
            background: rgba(255, 255, 255, 0.2);
        }
        .icon-btn.active {
            background: var(--accent); border-color: var(--accent);
        }
        .icon { width: 20px; height: 20px; }

        .glass-panel {
            background: var(--glass-bg);
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            border: 1px solid var(--glass-border);
            border-radius: 16px;
            padding: 24px;
            box-shadow: 0 4px 30px rgba(0, 0, 0, 0.3);
            margin-bottom: 24px;
        }
        .upload-controls {
            display: flex; flex-wrap: wrap; gap: 12px; align-items: center;
            margin-bottom: 18px;
        }
        .upload-controls input { color: #cbd5e1; max-width: 100%; }
        .upload-status { color: #94a3b8; font-size: 0.9rem; }

        /* List View */
        .file-container.list-view {
            display: flex; flex-direction: column; gap: 0;
        }
        .file-container.list-view .file-item {
            display: flex; justify-content: space-between; align-items: center;
            padding: 16px; border-bottom: 1px solid var(--glass-border);
            transition: background 0.2s;
        }
        .file-container.list-view .file-item:last-child { border-bottom: none; }
        .file-container.list-view .file-item:hover { background: rgba(255,255,255,0.05); border-radius: 8px;}
        .file-container.list-view .file-info { display: flex; align-items: center; gap: 12px; flex: 1; }
        .file-container.list-view .file-actions { display: flex; gap: 8px; align-items: center; }

        /* Grid View */
        .file-container.grid-view {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
            gap: 20px;
        }
        .file-container.grid-view .file-item {
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--glass-border);
            border-radius: 12px;
            padding: 16px;
            display: flex; flex-direction: column; align-items: center;
            transition: all 0.2s; text-align: center;
        }
        .file-container.grid-view .file-item:hover {
            transform: translateY(-4px); background: rgba(255, 255, 255, 0.08); box-shadow: 0 10px 20px rgba(0,0,0,0.2);
        }
        .file-container.grid-view .file-info { display: flex; flex-direction: column; align-items: center; gap: 12px; margin-bottom: 16px; width: 100%; }
        .file-container.grid-view .file-icon-large { width: 48px; height: 48px; color: var(--accent); }
        .file-container.grid-view .file-actions { display: flex; gap: 8px; width: 100%; justify-content: center; }
        .file-container.grid-view .file-name-text { word-break: break-all; font-size: 0.95rem; }

        .file-name { font-weight: 400; color: #e2e8f0; cursor: pointer; display: flex; align-items: center; gap: 12px; text-decoration: none;}
        .file-name:hover { color: var(--accent); }
        .file-size { color: #94a3b8; font-size: 0.85rem; }
        .breadcrumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 18px; color: #94a3b8; }
        .breadcrumb-button { background: none; border: none; color: #93c5fd; cursor: pointer; font: inherit; padding: 4px; }
        .breadcrumb-button:hover { color: white; text-decoration: underline; }
        .folder-button { color: #93c5fd; cursor: pointer; background: none; border: 0; font: inherit; }

        .btn-action {
            background: rgba(255, 255, 255, 0.1);
            border: 1px solid var(--glass-border);
            color: white;
            padding: 6px 12px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 0.85rem;
            transition: all 0.2s;
            text-decoration: none;
            display: flex; align-items: center; gap: 6px;
        }
        .btn-action:hover { background: var(--accent); border-color: var(--accent); }
        
        .btn-download { background: rgba(59, 130, 246, 0.2); border-color: rgba(59, 130, 246, 0.5); color: #60a5fa;}
        .btn-download:hover { background: var(--accent); color: white;}

        /* Modal */
        .modal-overlay {
            position: fixed; top: 0; left: 0; width: 100%; height: 100%;
            background: rgba(0, 0, 0, 0.8); backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
            display: flex; justify-content: center; align-items: center;
            z-index: 1000; opacity: 0; pointer-events: none; transition: opacity 0.3s;
        }
        .modal-overlay.active { opacity: 1; pointer-events: auto; }
        .modal-content {
            background: var(--glass-bg); border: 1px solid var(--glass-border);
            border-radius: 16px; padding: 24px; width: 90%; max-width: 800px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.5);
            position: relative; display: flex; flex-direction: column; align-items: center;
        }
        .modal-close {
            position: absolute; top: 16px; right: 16px;
            background: none; border: none; color: #f8fafc; cursor: pointer;
            padding: 8px; border-radius: 50%; transition: background 0.2s;
            display: flex; align-items: center; justify-content: center;
        }
        .modal-close:hover { background: rgba(255,255,255,0.1); }
        .modal-title { margin-top: 0; margin-bottom: 20px; font-weight: 600; font-size: 1.2rem; word-break: break-all; width: 100%; padding-right: 40px; box-sizing: border-box;}
        
        .media-container { width: 100%; display: flex; justify-content: center; background: rgba(0,0,0,0.3); border-radius: 8px; overflow: hidden; }
        video { max-width: 100%; max-height: 70vh; outline: none; }
        img.media-preview { max-width: 100%; max-height: 70vh; object-fit: contain; }
        audio { width: 100%; margin: 20px 0; outline: none; }
        
        .unsupported-media { padding: 40px; text-align: center; color: #94a3b8; }
        .modal-actions { margin-top: 20px; width: 100%; display: flex; justify-content: flex-end; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>☁️ Server Mobile</h1>
            <div class="view-controls">
                <button class="icon-btn active" id="btnList" onclick="setView('list')" title="Visualização em Lista">
                    <svg class="icon" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 10h16M4 14h16M4 18h16"></path></svg>
                </button>
                <button class="icon-btn" id="btnGrid" onclick="setView('grid')" title="Visualização em Grade">
                    <svg class="icon" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"></path></svg>
                </button>
            </div>
        </div>
        
        <div class="glass-panel">
            <nav class="breadcrumbs" id="breadcrumbs" aria-label="Caminho da pasta"></nav>
            <div class="upload-controls">
                <input type="file" id="uploadInput" multiple aria-label="Selecionar arquivos para enviar">
                <button class="btn-action btn-download" id="uploadButton" type="button">Enviar arquivos</button>
                <span class="upload-status" id="uploadStatus" role="status" aria-live="polite"></span>
            </div>
            <div class="file-container list-view" id="fileContainer">
                <div class="file-item"><span style="color:#94a3b8; padding: 16px;">Carregando arquivos...</span></div>
            </div>
        </div>
    </div>

    <!-- Modal -->
    <div class="modal-overlay" id="mediaModal" onclick="closeModal(event)">
        <div class="modal-content" onclick="event.stopPropagation()">
            <button class="modal-close" onclick="closeModal()">
                <svg width="24" height="24" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
            </button>
            <div class="modal-title" id="modalTitle">Nome do Arquivo</div>
            <div class="media-container" id="mediaPlayerContainer">
                <!-- Media player vai aqui -->
            </div>
            <div class="modal-actions">
                <a id="modalDownloadBtn" href="#" class="btn-action btn-download">
                    <svg class="icon" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
                    Baixar Arquivo
                </a>
            </div>
        </div>
    </div>

    <script>
        const fileContainer = document.getElementById('fileContainer');
        const modal = document.getElementById('mediaModal');
        const modalTitle = document.getElementById('modalTitle');
        const mediaPlayerContainer = document.getElementById('mediaPlayerContainer');
        const modalDownloadBtn = document.getElementById('modalDownloadBtn');
        const breadcrumbs = document.getElementById('breadcrumbs');
        const uploadInput = document.getElementById('uploadInput');
        const uploadButton = document.getElementById('uploadButton');
        const uploadStatus = document.getElementById('uploadStatus');
        let currentView = 'list';
        let currentPath = '';
        let allFiles = [];
        let activeMediaElement = null;

        function formatBytes(bytes) {
            if (bytes === 0) return '0 Bytes';
            const k = 1024, sizes = ['Bytes', 'KB', 'MB', 'GB'], i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }
        
        function getFileTypeInfo(file) {
            const mime = file.mimeType || '';
            
            if (mime.startsWith('video/')) return { type: 'video', icon: 'M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z' };
            if (mime.startsWith('audio/')) return { type: 'audio', icon: 'M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3' };
            if (mime.startsWith('image/')) return { type: 'image', icon: 'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z' };
            
            return { type: 'other', icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z' };
        }

        function setView(viewMode) {
            currentView = viewMode;
            document.getElementById('btnList').classList.toggle('active', viewMode === 'list');
            document.getElementById('btnGrid').classList.toggle('active', viewMode === 'grid');
            fileContainer.className = `file-container ${'$'}{viewMode}-view`;
            renderFiles();
        }

        function openModal(path, filename, type, mimeType) {
            const fileUrl = '/file?path=' + encodeURIComponent(path);
            const downloadUrl = '/download?path=' + encodeURIComponent(path);
            
            modalTitle.textContent = filename.includes('.') ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            modalDownloadBtn.href = downloadUrl;
            modalDownloadBtn.download = filename;
            
            mediaPlayerContainer.replaceChildren();
            activeMediaElement = null;

            if (type === 'video' || type === 'audio') {
                const media = document.createElement(type);
                activeMediaElement = media;
                media.controls = true;
                media.preload = 'auto';
                media.autoplay = true;
                media.playsInline = true;
                const source = document.createElement('source');
                source.src = fileUrl;
                source.type = mimeType;
                media.appendChild(source);
                mediaPlayerContainer.appendChild(media);
            } else if (type === 'image') {
                const image = document.createElement('img');
                image.src = fileUrl;
                image.alt = filename;
                image.className = 'media-preview';
                mediaPlayerContainer.appendChild(image);
            } else {
                const message = document.createElement('div');
                message.className = 'unsupported-media';
                message.textContent = 'Visualização não disponível para este tipo de arquivo. Você pode fazer o download para abri-lo localmente.';
                mediaPlayerContainer.appendChild(message);
            }
            
            modal.classList.add('active');
        }

        function closeModal(event) {
            if (event && event.target !== modal) return;
            modal.classList.remove('active');
            if (activeMediaElement) {
                activeMediaElement.pause();
                activeMediaElement.removeAttribute('src');
                activeMediaElement.querySelectorAll('source').forEach(source => source.removeAttribute('src'));
                activeMediaElement.load();
                activeMediaElement = null;
            }
            mediaPlayerContainer.replaceChildren();
        }

        function renderBreadcrumbs() {
            breadcrumbs.innerHTML = '';
            const parts = currentPath ? currentPath.split('/') : [];
            const locations = [{ name: 'Arquivos', path: '' }];
            parts.forEach((part, index) => locations.push({
                name: part,
                path: parts.slice(0, index + 1).join('/')
            }));
            locations.forEach((location, index) => {
                if (index > 0) {
                    const separator = document.createElement('span');
                    separator.textContent = '/';
                    breadcrumbs.appendChild(separator);
                }
                const button = document.createElement('button');
                button.className = 'breadcrumb-button';
                button.textContent = location.name;
                button.addEventListener('click', () => {
                    currentPath = location.path;
                    fetchFiles();
                });
                breadcrumbs.appendChild(button);
            });
        }

        function renderFiles() {
            renderBreadcrumbs();
            fileContainer.innerHTML = '';
            if(allFiles.length === 0) {
                fileContainer.innerHTML = '<div class="file-item" style="width:100%; justify-content:center;"><span style="color:#94a3b8; padding: 16px;">Nenhum arquivo encontrado.</span></div>';
                return;
            }
            
            allFiles.forEach(file => {
                const path = currentPath ? currentPath + '/' + file.name : file.name;
                if (file.isDirectory) {
                    const folder = document.createElement('div');
                    folder.className = 'file-item';
                    const folderName = document.createElement('button');
                    folderName.className = 'file-name folder-button';
                    folderName.textContent = '📁 ' + file.name;
                    folderName.addEventListener('click', () => {
                        currentPath = path;
                        fetchFiles();
                    });
                    folder.appendChild(folderName);
                    fileContainer.appendChild(folder);
                    return;
                }
                const fileInfo = getFileTypeInfo(file);
                const downloadUrl = '/download?path=' + encodeURIComponent(path);
                const item = document.createElement('div');
                item.className = 'file-item';
                const info = document.createElement('div');
                info.className = 'file-info';
                const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                icon.setAttribute('class', currentView === 'grid' ? 'icon file-icon-large' : 'icon');
                icon.setAttribute('fill', 'none');
                icon.setAttribute('stroke', 'currentColor');
                icon.setAttribute('viewBox', '0 0 24 24');
                const iconPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                iconPath.setAttribute('stroke-linecap', 'round');
                iconPath.setAttribute('stroke-linejoin', 'round');
                iconPath.setAttribute('stroke-width', '2');
                iconPath.setAttribute('d', fileInfo.icon);
                icon.appendChild(iconPath);
                const details = document.createElement('div');
                details.style.cssText = 'display:flex;flex-direction:column;align-items:' + (currentView === 'grid' ? 'center' : 'flex-start') + ';';
                const name = document.createElement('span');
                name.className = 'file-name-text';
                name.textContent = file.name;
                const size = document.createElement('span');
                size.className = 'file-size';
                size.textContent = formatBytes(file.size);
                details.append(name, size);
                info.append(icon, details);

                const actions = document.createElement('div');
                actions.className = 'file-actions';
                const preview = document.createElement('button');
                preview.className = 'btn-action';
                preview.textContent = 'Visualizar';
                preview.addEventListener('click', () => openModal(path, file.name, fileInfo.type, file.mimeType));
                const download = document.createElement('a');
                download.href = downloadUrl;
                download.download = file.name;
                download.className = 'btn-action btn-download';
                download.textContent = 'Baixar';
                actions.append(preview, download);
                item.append(info, actions);
                fileContainer.appendChild(item);
            });
        }

        async function fetchFiles() {
            try {
                const res = await fetch('/api/files?path=' + encodeURIComponent(currentPath));
                if (!res.ok) throw new Error('Falha ao carregar arquivos');
                allFiles = await res.json();
                renderFiles();
            } catch (e) {
                fileContainer.innerHTML = '<div class="file-item" style="width:100%; justify-content:center;"><span style="color:#ef4444; padding: 16px;">Erro ao carregar arquivos.</span></div>';
            }
        }

        uploadButton.addEventListener('click', async () => {
            const files = Array.from(uploadInput.files || []);
            if (files.length === 0) {
                uploadStatus.textContent = 'Selecione pelo menos um arquivo.';
                return;
            }

            uploadButton.disabled = true;
            let uploaded = 0;
            const failures = [];
            for (let index = 0; index < files.length; index++) {
                const file = files[index];
                uploadStatus.textContent = 'Enviando ' + (index + 1) + ' de ' + files.length + ': ' + file.name;
                try {
                    const params = new URLSearchParams({ filename: file.name });
                    const response = await fetch('/api/upload?' + params.toString(), {
                        method: 'POST',
                        headers: {
                            'Content-Type': file.type || 'application/octet-stream',
                            'X-File-Last-Modified': String(file.lastModified)
                        },
                        body: file
                    });
                    if (!response.ok) throw new Error(await response.text());
                    uploaded++;
                } catch (error) {
                    failures.push(file.name + ': ' + (error.message || 'falha no envio'));
                }
            }

            uploadButton.disabled = false;
            uploadInput.value = '';
            await fetchFiles();
            uploadStatus.textContent = failures.length
                ? uploaded + ' arquivo(s) enviado(s); falha(s): ' + failures.join(' | ')
                : uploaded + ' arquivo(s) enviado(s) para Downloads/ServerMobile.';
        });

        fetchFiles();
    </script>
</body>
</html>
    """.trimIndent()
}
