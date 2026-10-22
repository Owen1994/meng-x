import { useEffect, useState } from 'react';
import { api, getToken, clearToken } from './api';
import Login from './pages/Login';
import Chat from './pages/Chat';
import Board from './pages/Board';
import './App.css';

export default function App() {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);
  // 登录后主界面为访谈页，看板从顶栏进入
  const [view, setView] = useState('chat');

  // 启动时用本地 token 恢复登录态
  useEffect(() => {
    if (!getToken()) {
      setChecking(false);
      return;
    }
    api('/api/auth/me')
      .then((me) => setUser({ userId: me.userId, nickname: me.nickname }))
      .catch(() => clearToken())
      .finally(() => setChecking(false));
  }, []);

  if (checking) {
    return (
      <div className="page">
        <div className="moon" />
      </div>
    );
  }
  if (!user) {
    return <Login onLogin={setUser} />;
  }

  const logout = () => {
    clearToken();
    setUser(null);
    setView('chat');
  };

  if (view === 'board') {
    return <Board user={user} onBack={() => setView('chat')} onLogout={logout} />;
  }
  return <Chat user={user} onOpenBoard={() => setView('board')} onLogout={logout} />;
}
