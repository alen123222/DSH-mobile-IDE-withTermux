import { AsyncLocalStorage } from 'node:async_hooks';

// The Chinese source string is the key, so an untranslated message still reads
// correctly instead of leaking a placeholder. The language is per request: the
// app sends Accept-Language, and anything without it (tests, curl) stays zh.
const english = {
  '新会话': 'New chat',
  '会话不存在': 'That chat does not exist',
  '请先停止当前任务，再删除此对话': 'Stop the task before deleting this chat',
  'DSH 进程不可用': 'The DSH process is not available',
  '尚未安装 DSH，请先在环境页安装引擎': 'DSH is not installed; install the engine on the Setup page first',
  '此启动器面向 Termux；电脑端仅运行协议测试': 'This launcher targets Termux; a desktop only runs the protocol tests',
  'DSH 协议帧超出限制': 'The DSH protocol frame exceeded the limit',
  'DSH 返回了未知协议': 'DSH returned an unknown protocol',
  '模型名称': 'model name',
  'DSH SDK 版本不兼容': 'Incompatible DSH SDK version',
  'DSH 回执前事件过多': 'Too many events before the DSH receipt',
  '无效的 DSH 事件': 'Invalid DSH event',
  '无效的助手消息': 'Invalid assistant message',
  '请先允许此会话执行命令和修改文件': 'Allow this chat to run commands and edit files first',
  '此会话来自上次运行，请在侧栏新建对话继续': 'This chat is from a previous run; start a new one from the sidebar',
  '任务仍在运行': 'The task is still running',
  '此会话当前不可用': 'This chat is not available right now',
  '消息': 'message',
  '请在环境页保存 API Key': 'Save an API key on the Setup page',
  'API 连接或模型已更改，请新建会话使设置生效': 'The API connection or model changed; start a new chat to apply it',
  'DSH 消息回执': 'DSH message receipt',
  'DSH 请求已取消': 'The DSH request was cancelled',
  '不支持的 API 协议': 'Unsupported API protocol',
  'API 地址无效': 'Invalid API address',
  'API 地址须为 HTTP(S)，且不包含账号、查询参数或片段': 'The API address must be HTTP(S) with no credentials, query or fragment',
  'Pocket 自定义 API': 'Pocket custom API',
  '请求必须为 JSON': 'The request must be JSON',
  '请求内容过大': 'The request body is too large',
  '无效的 JSON 请求': 'Invalid JSON request',
  '浏览器来源不可调用本地执行服务': 'A browser origin cannot call the local execution service',
  '连接密钥不匹配，请重新连接 Termux': 'The pairing key does not match; reconnect Termux',
  '接口不存在': 'No such endpoint',
  '无效的终端游标': 'Invalid terminal cursor',
  '操作失败': 'The operation failed',
  '真实终端需要 Termux 或 POSIX 环境': 'A real terminal needs Termux or a POSIX environment',
  '目录不存在': 'The folder does not exist',
  '目录位于当前工作区之外': 'The folder is outside the current workspace',
  '请选择文件夹作为终端目录': 'Pick a folder as the terminal directory',
  '请先在 Termux 安装 python 和 bash': 'Install python and bash in Termux first',
  '最多同时打开 8 个终端': 'At most 8 terminals can be open at once',
  '\r\n终端协议错误\r\n': '\r\nTerminal protocol error\r\n',
  '终端已失效，请重新打开': 'The terminal expired; reopen it',
  '终端已退出': 'The terminal exited',
  '无效的终端输入': 'Invalid terminal input',
  '无效的终端尺寸': 'Invalid terminal size',
  '未知的终端操作': 'Unknown terminal action',
  '目录': 'folder',
  '请选择绝对路径': 'Choose an absolute path',
  '没有访问该目录的权限': 'No permission to access that folder',
  '该路径不是文件夹': 'That path is not a folder',
  'Termux 无法读写此目录，或存储为只读。请先授权共享存储；外接盘可尝试 ~/storage/external-1。Android 的文件选择器授权不会自动授予 Termux 路径权限。':
    'Termux cannot read or write this folder, or the storage is read-only. Grant shared storage first; for a removable drive try ~/storage/external-1. A document-picker grant in Android does not give Termux path access.',
  '目录不存在或外接存储已拔出，请连接后刷新': 'The folder does not exist or the drive was removed; reconnect and refresh',
  '请选择 Termux 可访问的文件路径；Android 文档 URI 不能作为命令工作目录':
    'Choose a path Termux can reach; an Android document URI cannot be a working directory',
  '工作区不存在': 'That workspace does not exist',
  '没有读取该目录的权限。共享存储需要先在 Termux 执行一次 termux-setup-storage。':
    'No permission to read that folder. Shared storage needs termux-setup-storage in Termux first.',
  '目录链接形成循环': 'The folder link forms a loop',
  '存储响应超时，请检查 Termux 存储权限或重新连接外接盘后刷新。': 'Storage timed out. Check Termux storage permissions or reconnect the removable drive and refresh.',
  '主目录': 'Home',
  '内部存储': 'Internal storage',
  '目录名': 'folder name',
  '目录名不能包含路径分隔符': 'A folder name cannot contain a path separator',
  '文件路径': 'file path',
  '文件不存在或已被移动': 'The file does not exist or was moved',
  '文件位于当前工作区之外，请添加对应工作区': 'The file is outside the current workspace; add that workspace',
  '请选择普通文件': 'Choose an ordinary file',
  '文件超过 ': 'File exceeds ',
  ' MB，未在应用内加载': ' MB and was not loaded in the app',
  ' MB，暂不支持在应用内打开': ' MB and cannot be opened in the app yet',
  '缺少文件内容': 'Missing file content',
  '内容过大，未保存': 'The content is too large and was not saved',
  '文件已被外部修改，请重新打开后再保存': 'The file changed elsewhere; reopen it before saving',
  ' 必须是有效的非空字符串': ' must be a valid, non-empty string',
  '这不是有效的 zip 压缩包': 'This is not a valid zip archive',
  '暂不支持 ZIP64 压缩包': 'ZIP64 archives are not supported yet',
  '压缩包已损坏': 'The archive is damaged',
  '压缩包使用了不支持的压缩方式': 'The archive uses an unsupported compression method',
  '压缩包超过 ': 'Archive exceeds ',
  ' MB，暂不支持解压': ' MB and cannot be extracted yet',
  '压缩包包含不安全的路径': 'The archive contains an unsafe path',
  '解压后内容过大': 'The extracted content is too large',
};

const storage = new AsyncLocalStorage();

/** Pick a language from Accept-Language; anything not English stays Chinese. */
export function languageOf(request) {
  const header = String(request?.headers?.['accept-language'] || '').trim().toLowerCase();
  if (header.startsWith('en') || header.includes(',en')) return 'en';
  return 'zh';
}

export function inLanguage(language, run) {
  return storage.run(language === 'en' ? 'en' : 'zh', run);
}

export function t(text) {
  if (storage.getStore() !== 'en') return text;
  return english[text] ?? text;
}
