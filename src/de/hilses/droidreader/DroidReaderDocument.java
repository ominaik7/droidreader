package de.hilses.droidreader;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Log;

public class DroidReaderDocument {
	/**
	 * interface for notification on ready rendered bitmaps
	 */
	interface RenderListener {
		/**
		 * is called by the RenderThread when a new Pixmap is ready
		 */
		public void onNewRenderedPixmap();
	}
	private class DummyRenderListener implements RenderListener {
		@Override public void onNewRenderedPixmap() {
			return;
		}
	}

	/**
	 * renders Pixmaps for PdfPage objects and manages a LIFO queue for requests
	 */
	class RenderThread extends Thread {
		static final String TAG = "DroidReaderRenderThread";
		/**
		 * Thread state keeper
		 */
		boolean mRun = true;
		long mLazyStart = 0;
		
		public void newRenderJob(long lazyStart) {
			Log.d(TAG, "got a new render job");
			// new job, so set the queue entrance's data:
			mLazyStart = lazyStart;
			// wake up the thread:
			interrupt();
		}
		
		/**
		 * Thread run() loop, stopping only when mRun is set to false
		 */
		@Override
		public void run() {
			while (mRun) {
				Log.d(TAG, "starting loop");
				
				try {
					// no new job, old job properly done,
					// so we have nothing to do...
					Log.d(TAG, "RenderThread going to sleep");
					Thread.sleep(3600000);
				} catch (InterruptedException e) {
					Log.d(TAG, "RenderThread woken up");
				}
				
				if(mRun) {
					boolean doRender = true;
					do {
						if(mLazyStart > 0) {
							try {
								Thread.sleep(mLazyStart);
								doRender = true;
							} catch(InterruptedException e) {
								doRender = false;
							}
						}
					} while(doRender == false);
				}
				
				if(mRun) {
					Log.d(TAG, "now rendering the current render job");
					synchronized(mDocumentLock) {
						if(mDocument.mHandle != 0 && mPage.mHandle != 0) {
							if(mMetadataDirty) {
								calcPageMetadata();
							}
							calcCenteredViewBox();
							try {
								Log.d(TAG, "now rendering: "+mViewBox.toShortString());
								int newView = (mCurrentView + 1) % DroidReaderDocument.BUFFERS;
								mViews[newView].render(mDocument, mPage, mViewBox, mPageMatrix);
								mCurrentView = newView;
							} catch (PageRenderException e) {
								// TODO: error handling
							}
						}
						mHavePixmap = true;
					}
					
					Log.d(TAG, "now alerting the RenderListener");
					mRenderListener.onNewRenderedPixmap();
				}
			}
			Log.d(TAG, "shutting down.");
		}
	}
	static final String TAG = "DroidReaderDocument";
	/**
	 * Constant: Zoom to fit page
	 */
	protected static final float ZOOM_FIT = -1F;
	/**
	 * Constant: Zoom to fit page width
	 */
	protected static final float ZOOM_FIT_WIDTH = -2F;
	/**
	 * Constant: Zoom to fit page height
	 */
	protected static final float ZOOM_FIT_HEIGHT = -3F;
	
	protected static final int RENDER_NOW = 0;
	
	protected static final int RENDER_LAZY = 250;
	
	protected static final int PAGE_LAST = -1;

	protected final RenderThread mRenderThread = new RenderThread();
	
	static final int BUFFERS = 1;
	
	final PdfDocument mDocument = new PdfDocument();
	final PdfPage mPage = new PdfPage();
	int mCurrentView = 0;
	final PdfView mViews[] = { new PdfView(), new PdfView() };
	
	boolean mMetadataDirty = false;
	boolean mHavePixmap = false;
	boolean mDoRender = false;
	
	float mZoom = 1.0F;
	int mDpiX = 160;
	int mDpiY = 160;
	int mTileMaxX = 1024;
	int mTileMaxY = 1024;
	int mRotation = 0;
	
	int mOffsetX = 0;
	int mOffsetY = 0;
	
	int mDisplaySizeX = 1;
	int mDisplaySizeY = 1;
	int mPageSizeX = 0;
	int mPageSizeY = 0;
	int mOffsetMaxX = 0;
	int mOffsetMaxY = 0;
	Rect mViewBox = new Rect(0, 0, 1, 1);
	Matrix mPageMatrix = new Matrix();
	boolean mIsScrollingX = false;
	boolean mIsScrollingY = false;
	
	RenderListener mRenderListener = new DummyRenderListener();
	
	final Object mDocumentLock = new Object();
	
	public DroidReaderDocument() {
		mRenderThread.start();
	}
	
	void open(String filename, String password, int pageNo, int offsetX, int offsetY) 
	throws PasswordNeededException, WrongPasswordException, CannotRepairException, CannotDecryptXrefException, PageLoadException
	{
		Log.d(TAG, "opening document: "+filename);
		synchronized(mDocumentLock) {
			mPage.close();
			mDocument.open(filename, password);
			mPage.open(mDocument, pageNo);
		}
		mOffsetX = offsetX;
		mOffsetY = offsetY;
		mHavePixmap = false;
		mMetadataDirty = true;
		render(false);
	}
	
	void openPage(int no, boolean isRelative)
	throws PageLoadException
	{
		Log.d(TAG, "opening page "+(isRelative?"(rel) ":"(abs) ")+no);
		synchronized(mDocumentLock) {
			int realPageNo = ((isRelative ? mPage.no : 0) + no);
			if(!isRelative && (realPageNo == PAGE_LAST))
				realPageNo = mDocument.pagecount;
			mPage.open(mDocument, realPageNo);
		}
		// TODO: introduce offsets here, too
		mOffsetX = 0;
		mOffsetY = 0;
		mHavePixmap = false;
		mMetadataDirty = true;
		render(false);
	}
	
	boolean havePage(int no, boolean isRelative) {
		synchronized(mDocumentLock) {
			int realPageNo = ((isRelative ? mPage.no : 0) + no);
			if((realPageNo < 1) || (realPageNo > mDocument.pagecount))
				return false;
		}
		return true;
	}
	
	boolean isPageLoaded() {
		synchronized(mDocumentLock) {
			if(mDocument.mHandle == 0 || mPage.mHandle == 0)
				return false;
		}
		return true;
	}
	
	boolean havePixmap() {
		return mHavePixmap;
	}
	
	void render(long lazyStart) {
		if(mDoRender) {
			mRenderThread.newRenderJob(lazyStart);
		}
	}
	
	void render(boolean lazyStart) {
		if(lazyStart) {
			render(RENDER_LAZY);
		} else {
			render(RENDER_NOW);
		}
	}
	
	void setDpi(int x, int y) {
		Log.d(TAG, "setDpi: "+x+","+y);
		mDpiX = x;
		mDpiY = y;
		mMetadataDirty = true;
		render(false);
	}
	
	void setTileMax(int x, int y) {
		Log.d(TAG, "setTileMax: "+x+","+y);
		mTileMaxX = x;
		mTileMaxY = y;
		mMetadataDirty = true;
		render(false);
	}
	
	void setRotation(int degrees, boolean isRelative) {
		Log.d(TAG, "setRotation: "+(isRelative?"(rel) ":"(abs) ")+degrees+"°");
		mRotation = ((isRelative ? mRotation : 0) + degrees + 360) % 360;
		mMetadataDirty = true;
		render(false);
	}
	
	void setZoom(float zoom, boolean isRelative) {
		Log.d(TAG, "setZoom: "+(isRelative?"(rel) ":"(abs) ")+zoom);
		mZoom = (isRelative ? mZoom : 1) * zoom;
		mMetadataDirty = true;
		render(false);
	}
	
	void offset(int x, int y, boolean isRelative) {
		Log.d(TAG, "offset: "+(isRelative?"(rel) ":"(abs) ")+x+","+y);
		
		if(mMetadataDirty)
			calcPageMetadata();
		
		mOffsetX = (isRelative ? mOffsetX : 0) + x;
		mOffsetY = (isRelative ? mOffsetY : 0) + y;
		
		if(mOffsetX > mOffsetMaxX) mOffsetX = mOffsetMaxX;
		else if(mOffsetX < 0) mOffsetX = 0;
		
		if(mOffsetY > mOffsetMaxY) mOffsetY = mOffsetMaxY;
		else if(mOffsetY < 0) mOffsetY = 0;
		
		if(!withinViewBox()) {
			render(true);
		}
	}
	
	void startRendering(int displaySizeX, int displaySizeY) {
		Log.d(TAG, "startRendering");
		mDisplaySizeX = displaySizeX;
		mDisplaySizeY = displaySizeY;
		mMetadataDirty = true;
		mDoRender = true;
		render(false);
	}
	
	void stopRendering() {
		Log.d(TAG, "stopRendering");
		mDoRender = false;
	}
	
	private void calcPageMetadata() {
		Log.d(TAG, "calcPageMetadata()");
		float zoomX = mZoom * mDpiX / 72;
		float zoomY = mZoom * mDpiY / 72;
		float pageHeight = mPage.mMediabox[3]-mPage.mMediabox[1];
		float pageWidth = mPage.mMediabox[2]-mPage.mMediabox[0];
		mPageMatrix.reset();
		// mirror on X-axis (because of different coord systems)
		mPageMatrix.postScale(1, -1);
		// move left by <left border> px and up by <"bottom" of page> pixels
		mPageMatrix.postTranslate(-mPage.mMediabox[0], mPage.mMediabox[3]);
		// now do the rotation
		mPageMatrix.postRotate(mPage.rotate + mRotation);
		// calculate proper zoom values and correct offsets resulting from rotation
		if(((mPage.rotate + mRotation) % 360) == 90) {
			mPageMatrix.postTranslate(pageHeight, 0);
		} else if(((mPage.rotate + mRotation) % 360) == 180) {
			mPageMatrix.postTranslate(pageWidth, pageHeight);
		} else if(((mPage.rotate + mRotation) % 360) == 270) {
			mPageMatrix.postTranslate(0, pageWidth);
		}

		if(((mPage.rotate + mRotation) % 180) == 90) {
			float save = pageHeight;
			pageHeight = pageWidth;
			pageWidth = save;
		}
		
		if(mZoom == ZOOM_FIT || mZoom == ZOOM_FIT_HEIGHT) {
			zoomX = zoomY = mDisplaySizeY / pageHeight;
		}
		if(mZoom == ZOOM_FIT || mZoom == ZOOM_FIT_WIDTH) {
			float zoom = mDisplaySizeX / pageWidth;
			if(mZoom == ZOOM_FIT) {
				if(zoom < zoomY)
					zoomY = zoomX = zoom;
			} else {
				zoomY = zoomX = zoom;
			}
		}
		
		mPageMatrix.postScale(zoomX, zoomY);
		mPageSizeX = (int) (pageWidth * zoomX);
		mPageSizeY = (int) (pageHeight * zoomY);
		
		if(mPageSizeX <= mDisplaySizeX) {
			mIsScrollingX = false;
			mOffsetX = 0;
			mOffsetMaxX = 0;
		} else {
			mIsScrollingX = true;
			mOffsetMaxX = mPageSizeX - mDisplaySizeX;
		}
		if(mPageSizeY <= mDisplaySizeY) {
			mIsScrollingY = false;
			mOffsetY = 0;
			mOffsetMaxY = 0;
		} else {
			mIsScrollingY = true;
			mOffsetMaxY = mPageSizeY - mDisplaySizeY;
		}
		
		mMetadataDirty = false;
		Log.d(TAG, "calculated new display metadata");
	}
	
	private boolean withinViewBox() {
		if(mIsScrollingX &&
				(((mOffsetX + mDisplaySizeX) > mViewBox.right) ||
				(mOffsetX < mViewBox.left)))
			return false;
		if(mIsScrollingY &&
				(((mOffsetY + mDisplaySizeY) > mViewBox.bottom) ||
				(mOffsetY < mViewBox.left)))
			return false;
		return true;
	}
	
	void calcCenteredViewBox() {
		int offsetX = mOffsetX - ((mTileMaxX - mDisplaySizeX) / 2);
		int offsetY = mOffsetY - ((mTileMaxY - mDisplaySizeY) / 2);
		if(offsetX < 0)
			offsetX = 0;
		if(offsetY < 0)
			offsetY = 0;
		Log.d(TAG, "calcCenteredViewBox: new offsets "+offsetX+","+offsetY);
		mViewBox.set(offsetX, offsetY, 
				((mPageSizeX <= mTileMaxX)?mPageSizeX:(offsetX+mTileMaxX)),
				((mPageSizeY <= mTileMaxY)?mPageSizeY:(offsetY+mTileMaxY)));
	}
}
